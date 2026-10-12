package com.huang.yupicture.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.constant.ImageConstant;
import com.huang.yupicture.constant.UserConstant;
import com.huang.yupicture.manager.CosManager;
import com.huang.yupicture.manager.OutboundHttpClient;
import com.huang.yupicture.mapper.ImageMapper;
import com.huang.yupicture.model.dto.image.ImageDownloadResult;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.ImageOptionsVO;
import com.huang.yupicture.model.vo.ImageVO;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.ImageUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.utils.ParamUtils;
import com.huang.yupicture.utils.UrlPictureUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 图片业务实现。
 *
 * <p><b>本类的核心是「两级权限」：本人 or 管理员。</b>
 * user 模块的管理员接口是"只有管理员能进"（{@code checkAdminUser()} 一个判断），
 * 图片模块的编辑 / 删除是"自己的能改、别人的得管理员才能改" —— 判断条件从
 * "你是谁"变成了"你是谁 + 这东西是谁的"。
 */
@Slf4j
@Service
public class ImageServiceImpl extends ServiceImpl<ImageMapper, Image> implements ImageService {

    @Resource
    private UserService userService;

    /**
     * COS 操作封装。
     *
     * <p>用 {@code @Autowired(required = false)} 而不是 {@code @Resource}：
     * 没配 {@code cos.client.*} 时 {@link CosManager} 这个 bean 根本不存在（条件装配），
     * 必填注入会让<b>整个应用启动失败</b> —— 一个还没配好的外部服务不该让所有功能都起不来。
     * 这里允许它为 null，在真正要上传时给出明确提示（见 {@link #uploadImage}）。
     */
    @Autowired(required = false)
    private CosManager cosManager;

    /**
     * JSON 读写（标签是 JSON 数组字符串，需要序列化 / 反序列化）。
     *
     * <p>用注入的共享实例而不是到处 {@code new ObjectMapper()}：ObjectMapper
     * 是线程安全且带缓存的，每处新建既浪费又绕过了 Spring 的统一配置。
     */
    @Resource
    private ObjectMapper objectMapper;

    /** 对外 HTTP 抓取（拉远程图片）：走共享客户端，代理/超时/重定向统一在这儿配 */
    @Resource
    private OutboundHttpClient outboundHttpClient;

    @Override
    public ImageVO getImageById(String id) {
        // getLoginUser() 内部已做登录校验：未登录抛 NOT_LOGIN_ERROR(40100)
        User loginUser = userService.getLoginUser();

        Image image = this.getById(ParamUtils.parseId(id));
        if (image == null) {
            // 用 NOT_FOUND_ERROR(40400) 而不是 PARAMS_ERROR(40000)：id 格式没问题，
            // 是"这条数据不存在"，语义更准。⚠️ user 模块当时用的是 40000，两处不一致，
            // 收官时统一（改 user 会动到已通过的测试断言，所以没顺手改）。
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        checkImageVisible(image, loginUser);
        return toImageVO(image);
    }

    @Override
    public Page<ImageVO> listImageByPage(ImageQueryRequest imageQueryRequest) {
        if (imageQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser();

        // 分页加固统一走 PageRequest 的两个方法（逻辑与 user 模块共用一份）
        long pageNum = imageQueryRequest.resolvePageNum();
        long pageSize = imageQueryRequest.resolvePageSize();

        LambdaQueryWrapper<Image> wrapper = new LambdaQueryWrapper<>();

        // ① 权限分流：这一步决定"这次查询能看见谁的数据"
        applyUserScope(wrapper, imageQueryRequest, loginUser);

        // ② 查询条件（都是"选填"，传了才加）
        if (!ParamUtils.isBlank(imageQueryRequest.getId())) {
            wrapper.eq(Image::getId, ParamUtils.parseId(imageQueryRequest.getId()));
        }
        if (!ParamUtils.isBlank(imageQueryRequest.getName())) {
            wrapper.like(Image::getName, imageQueryRequest.getName());
        }
        if (!ParamUtils.isBlank(imageQueryRequest.getIntroduction())) {
            wrapper.like(Image::getIntroduction, imageQueryRequest.getIntroduction());
        }
        if (!ParamUtils.isBlank(imageQueryRequest.getCategory())) {
            wrapper.eq(Image::getCategory, imageQueryRequest.getCategory());
        }
        applyTagsFilter(wrapper, imageQueryRequest.getTags());
        if (!ParamUtils.isBlank(imageQueryRequest.getPicFormat())) {
            wrapper.eq(Image::getPicFormat, imageQueryRequest.getPicFormat());
        }
        // 主色调筛选：入参是色板标准色 → 归类成色系 → 按 picColorFamily 等值匹配
        // （分流收在 applyColorFilter，与 applyTagsFilter 同一写法）
        if (!ParamUtils.isBlank(imageQueryRequest.getPicColor())) {
            applyColorFilter(wrapper, imageQueryRequest.getPicColor());
        }

        // 审核状态筛选（本人 / 管理员都可用：管理员看整个待办队列，本人看自己哪张被拒了）
        if (imageQueryRequest.getReviewStatus() != null) {
            wrapper.eq(Image::getReviewStatus, imageQueryRequest.getReviewStatus());
        }

        // ③ 排序：时间倒序 + id 兜底
        //    createTime 只精确到秒（DDL 是 DATETIME 无小数位），同一秒上传的多张图之间
        //    顺序不确定 —— 翻页时会重复或漏项。加 id 做第二排序键，让顺序唯一确定。
        wrapper.orderByDesc(Image::getCreateTime).orderByDesc(Image::getId);

        Page<Image> imagePage = this.page(new Page<>(pageNum, pageSize), wrapper);

        // ④ 实体分页 → VO 分页（照搬 user 模块的写法，四步都在，看得见）
        Page<ImageVO> imageVoPage = new Page<>(imagePage.getCurrent(), imagePage.getSize(), imagePage.getTotal());
        List<ImageVO> voList = imagePage.getRecords().stream()
                .map(this::toImageVO)
                .collect(Collectors.toList());
        imageVoPage.setRecords(voList);
        return imageVoPage;
    }

    @Override
    public boolean updateImage(ImageUpdateRequest imageUpdateRequest) {
        if (imageUpdateRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser();
        long imageId = ParamUtils.parseId(imageUpdateRequest.getId());

        // 先查出来：既为了判断存在，也为了拿 userId 做归属校验
        Image oldImage = this.getById(imageId);
        if (oldImage == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        checkImageOwner(oldImage, loginUser);

        // 只 set 允许修改的字段；没 set 的字段保持 null
        // → MyBatis-Plus 默认 NOT_NULL 更新策略会跳过 null 列，等于"没传就不改"（PATCH 语义）
        // ⚠️ 千万别图省事写成 this.updateById(oldImage)：那样会把数据库里查出来的
        //    url / userId / createTime 原样再写一遍，看着结果一样，但一旦有人改过这几行代码
        //    就可能把别人的图"过户"到自己名下。用新的空对象，能改的字段是显式列出来的。
        Image image = new Image();
        image.setId(imageId);
        image.setName(imageUpdateRequest.getName());
        image.setIntroduction(imageUpdateRequest.getIntroduction());
        image.setCategory(imageUpdateRequest.getCategory());
        // 标签落库前规范化：见 normalizeTags 的说明（库里必须只有一个格式）
        image.setTags(normalizeTags(imageUpdateRequest.getTags()));

        // ========== 编辑会「重新触发审核」（官方流程） ==========
        // 图的内容能被改，审核结论就可能过期 —— 所以编辑必须让审核状态重新走一遍：
        //  - 普通用户改：退回待审核。**只重置状态，旧审核参数留在库里当历史**
        //    （「上次谁因为什么拒了我」是用户申辩的依据，不该被抹掉）
        //  - 管理员改：自动过审并补齐参数（审核人=创建人、时间=当前、原因=管理员自动过审）
        if (isAdmin(loginUser)) {
            image.setReviewStatus(ImageConstant.REVIEW_STATUS_PASS);
            image.setReviewerId(oldImage.getUserId());
            image.setReviewTime(new Date());
            image.setReviewMessage(ImageConstant.AUTO_REVIEW_MESSAGE);
        } else {
            image.setReviewStatus(ImageConstant.REVIEW_STATUS_PENDING);
        }

        boolean result = this.updateById(image);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新失败");
        }
        log.info("编辑图片：id={}, 操作人id={}, 审核状态重置为={}", imageId, loginUser.getId(),
                isAdmin(loginUser) ? "自动过审" : "待审核");
        return true;
    }

    @Override
    public boolean deleteImage(String id) {
        User loginUser = userService.getLoginUser();
        long imageId = ParamUtils.parseId(id);

        Image image = this.getById(imageId);
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        checkImageOwner(image, loginUser);

        // ⚠️ 顺序：先删存储上的文件，再删数据库记录。理由——
        //   第二步失败：记录还在、文件没了 → 用户看得见"图裂了"，再点一次删除即可自愈
        //   顺序反过来：记录没了、文件还在 → 谁都不会发现，白占容量还一直计费
        //   原则：宁可留下"能被看见的错误"，也不留下"不会被发现的错误"。
        if (cosManager == null) {
            log.warn("对象存储未配置，跳过文件清理（只删数据库记录）：imageId={}", imageId);
        } else {
            String key = null;
            try {
                key = cosManager.getKeyFromUrl(image.getUrl());
            } catch (BusinessException e) {
                // url 不是本项目的 COS 地址格式（比如手工导入的历史数据）→ 定位不到文件。
                // 这种情况不能让"删不了文件"把整个删除卡死，记下来继续删记录。
                log.warn("图片地址无法解析出存储路径，跳过文件清理：imageId={}, url={}", imageId, image.getUrl());
            }
            if (key != null) {
                // 删文件本身失败会抛异常并中止本次删除 —— 这是有意的：
                // 让用户看到"删除失败，请重试"，远好过悄悄留下一个没人引用的孤儿文件
                cosManager.delete(key);
                // 缩略图跟着删：COS 删除不存在的对象不报错（幂等），所以
                // 旧图没缩略图 / 缩略图当年上传失败，这里都不会炸 —— 尽力而为的清理
                try {
                    cosManager.deleteThumbnail(key);
                } catch (BusinessException e) {
                    // 缩略图清理失败不中止删除：原图都删了，别让一个派生文件卡死主流程。
                    // 但必须留痕 —— 这条缩略图从此变成没人引用的孤儿，要人工/定时任务兜底
                    log.warn("缩略图删除失败（需人工清理）：imageKey={}", key, e);
                }
                // webp 副本与缩略图同策略：尽力清理，失败留痕不阻断
                try {
                    cosManager.deleteWebp(key);
                } catch (BusinessException e) {
                    log.warn("WebP 副本删除失败（需人工清理）：imageKey={}", key, e);
                }
            }
        }

        boolean result = this.removeById(imageId);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除失败");
        }
        log.info("删除图片：id={}, 操作人id={}", imageId, loginUser.getId());
        return true;
    }

    @Override
    public ImageVO uploadImage(MultipartFile file, String name) {
        // 上传同样要求登录：userId 取自登录态，不接受前端传
        // （能传 userId 就等于能把图挂到别人名下，或者用别人的名义传违规内容）
        User loginUser = userService.getLoginUser();

        // ========== 阶段一：校验（任何一步不过就结束，不消耗任何存储） ==========
        // ① 非空 / 大小 / 后缀白名单
        String suffix = ImageUtils.checkAndGetSuffix(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败，请重试");
        }

        // ② 内容校验：文件头魔数必须与后缀一致（防"改个名就说是图片"）
        ImageUtils.checkContent(bytes, suffix);

        // ========== 阶段一点五：压缩（在魔数校验之后、存储之前） ==========
        // 位置有讲究：放在校验之前 = 白白压缩将被拒绝的文件；放在落库之后 = 压缩失去意义。
        // 校验已保证"这是一张真图"，此时压缩正好夹在"确认值得处理"和"付出存储成本"之间。
        long originalSize = bytes.length;
        bytes = ImageUtils.compress(bytes, suffix);
        // ⚠️ 从这里开始 bytes 已是"最终上库的内容"—— 下面的 size / readSize / COS 上传
        //    全部基于它，不能再用 file.getSize()（那是压缩前的原始大小）
        if (bytes.length < originalSize) {
            log.info("图片压缩生效：{} -> {} 字节（节省 {}%）",
                    originalSize, bytes.length,
                    100 - bytes.length * 100 / originalSize);
        }

        String imageName = resolveImageName(name, file.getOriginalFilename());
        if (imageName.length() > 256) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片名称不能超过 256 个字符");
        }
        return persistProcessedImage(loginUser, bytes, suffix, imageName, file.getContentType());
    }

    /**
     * URL 传图：服务器替用户去拉远端图片，后续处理与文件上传完全一致。
     *
     * <p><b>与文件上传的三点差异（只差这三点，汇合点之后严格一致）：</b>
     * <ol>
     *   <li>字节来源：HttpUtil 拉远端，而不是读 MultipartFile</li>
     *   <li>后缀推断：URL 路径尾段可能没有后缀（很多图床是 /abc123 无后缀）——
     *       推断不出时<b>不猜</b>，用魔数反推：真 jpg 的头是 FF D8 FF，能唯一确定格式</li>
     *   <li>名称兜底：URL 路径尾段（很多图床的尾段是无意义哈希，用户传了 name 就覆盖）</li>
     * </ol>
     *
     * <p><b>SSRF 防线在 {@link UrlPictureUtils#checkUrl}</b>：内网 / 回环 /
     * 云元数据地址在发起请求之前就拒绝。防线必须在"发请求"之前 ——
     * 拉完再判等于已经把请求发出去了，防线形同虚设。
     *
     * <p><b>超时必须显式设</b>：Hutool 默认不超时，一个慢速外站能把 Tomcat
     * 工作线程挂住几十秒 —— 几个恶意请求就能耗光线程池。
     */
    @Override
    public ImageVO uploadImageByUrl(String fileUrl, String name) {
        User loginUser = userService.getLoginUser();

        // ① URL 形状校验（协议 / 主机 / SSRF）—— 在发请求之前
        java.net.URI uri = UrlPictureUtils.checkUrl(fileUrl);

        // ② 拉取（JDK 内置 HttpClient，零新依赖 —— 为一个"拉字节"引 Hutool 不划算）。
        //    显式超时：连接 5s / 读取 10s。不设超时的话一个慢速外站能把 Tomcat
        //    工作线程挂住几十秒 —— 几个恶意请求就能耗光线程池。
        byte[] bytes;
        try {
            // 复用共享 HttpClient：超时/重定向/（可选的）代理都收口在 OutboundHttpClient 一处。
            // 原来在这里 new 一个客户端，"要不要走代理"这个环境事实就得在两处各改一遍 —— 必漏一处
            java.net.http.HttpClient client = outboundHttpClient.get();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();
            java.net.http.HttpResponse<byte[]> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "图片拉取失败（远端返回 " + response.statusCode() + "）");
            }
            bytes = response.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("拉取远程图片失败：url={}", fileUrl, e);
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片拉取失败，请确认地址可访问");
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "远端地址没有返回内容");
        }
        if (bytes.length > UrlPictureUtils.MAX_URL_IMAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片大小不能超过 5MB");
        }

        // ③ 内容判定：魔数是唯一权威。URL 后缀只做"线索"，做不了"证据"
        String suffix = resolveSuffixFromUrl(uri);
        if (suffix == null) {
            suffix = ImageUtils.detectSuffixByMagic(bytes);
            if (suffix == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "无法识别图片格式（地址无后缀且内容不是 jpg/png/webp）");
            }
        }
        // 魔数复核：远端自称 jpg 实际是别的格式 → 拒绝（与文件上传同一条铁律）
        ImageUtils.checkContent(bytes, suffix);

        String imageName = resolveImageName(name, extractFileNameFromUrl(uri));
        return persistProcessedImage(loginUser, bytes, suffix, imageName, null);
    }

    /**
     * 从 URL 路径尾段推断后缀。
     *
     * <p>只信路径部分（{@code getPath}），不信 query ——
     * {@code https://cdn.example.com/abc.jpg?token=xxx} 的尾段是 {@code abc.jpg}；
     * 而 {@code https://api.example.com/img?format=jpg} 的"后缀"在参数里，
     * 那是服务端行为不是文件名，不能当依据。
     *
     * @return 小写后缀（jpg/png/webp 之一）；推断不出返回 null（交给魔数反推）
     */
    private String resolveSuffixFromUrl(java.net.URI uri) {
        String path = uri.getPath();
        if (ParamUtils.isBlank(path)) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = tail.lastIndexOf('.');
        if (dot < 0 || dot == tail.length() - 1) {
            return null;
        }
        String suffix = tail.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ImageUtils.isSupportedSuffix(suffix) ? suffix : null;
    }

    /** URL 传图的名称兜底：路径尾段（通常是文件名）；空就交给 resolveImageName 的"未命名图片" */
    private String extractFileNameFromUrl(java.net.URI uri) {
        String path = uri.getPath();
        if (ParamUtils.isBlank(path)) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String tail = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = tail.lastIndexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }

    /**
     * 上传处理链的「汇合点」：从魔数校验通过之后的每一步（压缩 → COS → 派生文件 → 落库）。
     *
     * <p>抽出来的判据就是那条老规矩 ——「第二处出现时立刻抽」：文件上传（uploadImage）
     * 和 URL 传图（uploadImageByUrl）两条入口，从"拿到字节 + 确定后缀"开始，
     * 后面的每一步完全一致。两条路各写一遍 = 改压缩逻辑时必然漏一边。
     *
     * <p>入口差异（各自的 uploadImage / uploadImageByUrl 负责）：
     * <ul>
     *   <li>字节从哪来：文件读 MultipartFile / URL 拉远端</li>
     *   <li>后缀怎么定：文件看原始文件名 / URL 看路径尾部（拉取后用魔数复核）</li>
     *   <li>名称兜底：文件用原始文件名 / URL 用主机 + 路径尾段</li>
     * </ul>
     * 汇合点之后（含）的所有行为两条路严格一致 —— 这是"同一个业务只有一个标准"的落点。
     */
    private ImageVO persistProcessedImage(User loginUser, byte[] bytes, String suffix,
                                          String imageName, String contentType) {
        // ========== 阶段一点五：压缩（在魔数校验之后、存储之前） ==========
        // 位置有讲究：放在校验之前 = 白白压缩将被拒绝的文件；放在落库之后 = 压缩失去意义。
        // 校验已保证"这是一张真图"，此时压缩正好夹在"确认值得处理"和"付出存储成本"之间。
        long originalSize = bytes.length;
        bytes = ImageUtils.compress(bytes, suffix);
        // ⚠️ 从这里开始 bytes 已是"最终上库的内容"—— 下面的 size / readSize / COS 上传
        //    全部基于它，不能再用 file.getSize()（那是压缩前的原始大小）
        if (bytes.length < originalSize) {
            log.info("图片压缩生效：{} -> {} 字节（节省 {}%）",
                    originalSize, bytes.length,
                    100 - bytes.length * 100 / originalSize);
        }

        // ========== 阶段二：上传对象存储 ==========
        // 存储可用性检查特意放在参数校验之后：参数不对是最常见的情况，应当先报 40000；
        // 顺序反了会让"传了个空文件"也报成"对象存储未配置"，把排查方向带偏。
        if (cosManager == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "对象存储未配置，无法上传（请在 application-local.yml 中配置 cos.client.*）");
        }
        String key = cosManager.upload(bytes, suffix, contentType);
        String url = cosManager.buildUrl(key);        // ========== 阶段二点五：缩略图（增强，不是主功能） ==========
        // 生成 / 上传失败只记 warn 降级：缩略图挂了列表页回退原图，主功能"能传图"不受连坐。
        // 对比原图上传（失败必须抛）：原图是用户要的核心产物，缩略图是派生产物，
        // 派生物的失败不该让核心操作白跑。
        String thumbnailKey = null;
        byte[] thumbnailBytes = ImageUtils.thumbnail(bytes, suffix);
        if (thumbnailBytes != null) {
            try {
                thumbnailKey = cosManager.uploadThumbnail(thumbnailBytes, key);
            } catch (BusinessException e) {
                log.warn("缩略图上传失败，列表页将回退原图：imageKey={}", key, e);
            }
        }

        // ========== 阶段二点六：WebP 副本（增强，失败同样降级不阻断） ==========
        // 输入是"压缩后的字节"：能转出的 webp 是对压缩结果再优化，不是对原始大图。
        // 已是 webp 的图 / 转完不比现在小的图，toWebp 返回 null，跳过即可。
        String webpKey = null;
        if (!"webp".equals(suffix)) {
            byte[] webpBytes = ImageUtils.toWebp(bytes);
            if (webpBytes != null) {
                try {
                    webpKey = cosManager.uploadWebp(webpBytes, key);
                } catch (BusinessException e) {
                    log.warn("WebP 副本上传失败，详情页将回退原图：imageKey={}", key, e);
                }
            }
        }

        // ========== 阶段三：落库 ==========
        Image image = new Image();
        image.setUrl(url);
        image.setName(imageName);
        image.setPicSize((long) bytes.length);
        image.setPicFormat(suffix);
        image.setCreateTime(new Date());
        // 宽高 / 宽高比：webp 用 JDK 的 ImageIO 读不出来，那就留 null。
        // 不能写 0 —— 前端按 宽/高 算比例会得到 NaN，比"缺一个字段"糟糕得多。
        int[] size = ImageUtils.readSize(bytes);
        if (size != null && size[1] > 0) {
            image.setPicWidth(size[0]);
            image.setPicHeight(size[1]);
            image.setPicScale((double) size[0] / size[1]);
        }
        // ========== 主色调（按颜色搜索的检索维度） ==========
        // 与缩略图 / WebP 同一条纪律：提取失败返回 null 只记日志降级，绝不阻断上传。
        // null 的语义是「这张图不参与颜色筛选」，存量图（本功能之前传的）同样是 null —— 一致。
        String mainColor = ImageUtils.extractMainColor(bytes);
        if (mainColor != null) {
            image.setPicColor(mainColor);
            // 色系（筛选入口）跟着主色一起写：主色是连续值（#FDF9FB），色板是离散标准色
            // （#FFFFFF），筛选必须按色系等值匹配才对得上「点白色筛出所有白图」的预期。
            // 两者在同一个代码路径里一次算出，不存在「先写了主色、忘了写色系」的窗口。
            image.setPicColorFamily(ImageUtils.classifyColorFamily(mainColor));
        } else {
            log.warn("主色提取失败，该图不参与颜色筛选：name={}", imageName);
        }
        image.setUserId(loginUser.getId());
        // ========== 审核状态的初始值：按「谁传的」分流（官方流程） ==========
        // 管理员传的图不存在「管理员审自己」这一环，直接过审并补齐审核参数；
        // 普通用户传的图进待审核队列。审核是事后管理不是准入门槛（requirements 拍板）。
        if (isAdmin(loginUser)) {
            image.setReviewStatus(ImageConstant.REVIEW_STATUS_PASS);
            image.setReviewerId(loginUser.getId());          // 审核人 = 创建人
            image.setReviewTime(new Date());
            image.setReviewMessage(ImageConstant.AUTO_REVIEW_MESSAGE);
        } else {
            image.setReviewStatus(ImageConstant.REVIEW_STATUS_PENDING);
        }

        boolean result = this.save(image);
        if (!result) {
            // ⚠️ 补偿动作：数据库写失败，就把刚传上去的文件删掉。
            // 不删的话这个文件永远不会有记录指向它（"孤儿文件"），白占容量还一直计费，
            // 而且没有任何人会收到报错 —— 属于"不会被发现的错误"，必须主动清理。
            log.error("图片记录保存失败，回滚已上传的文件：key={}", key);
            try {
                cosManager.delete(key);
                // 缩略图跟着一起回滚：库里没有记录指向它们，留在桶上就是永远没人管的孤儿文件
                if (thumbnailKey != null) {
                    cosManager.delete(thumbnailKey);
                }
                if (webpKey != null) {
                    cosManager.delete(webpKey);
                }
            } catch (Exception e) {
                // 回滚也失败：只能留日志给人工/定时任务兜底，但绝不能掩盖原始失败原因
                log.error("回滚删除 COS 文件失败，需人工清理：key={}, thumbnailKey={}", key, thumbnailKey, e);
            }
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "上传失败，请重试");
        }

        log.info("上传图片成功：imageId={}, userId={}, size={}字节",
                image.getId(), loginUser.getId(), image.getPicSize());
        return toImageVO(image);
    }

    /**
     * 决定图片名称：没传就用原始文件名去掉后缀。
     *
     * <p>后缀要去掉 —— 用户看到的应该是「西湖日落」，而不是「西湖日落.jpg」
     * （格式已经在 {@code picFormat} 字段里单独记着了）。
     */
    private String resolveImageName(String name, String originalFilename) {
        if (!ParamUtils.isBlank(name)) {
            return name;
        }
        if (ParamUtils.isBlank(originalFilename)) {
            return "未命名图片";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        // dotIndex > 0：跳过"纯扩展名"（如 ".jpg"）和没有点的情况，避免切出空串
        return dotIndex > 0 ? originalFilename.substring(0, dotIndex) : originalFilename;
    }

    @Override
    public ImageOptionsVO listOptions() {
        // 登录校验：筛选项是"浏览"的辅助，浏览本身要登录（与列表接口保持一致）
        userService.getLoginUser();

        ImageOptionsVO vo = new ImageOptionsVO();

        // 分类：交给数据库 DISTINCT。只取已通过的图 —— 待审核图上的分类出现在公开筛选栏
        // 等于提前泄露了还没过审的内容长什么样。
        // 注意列名：本项目的库列是驼峰（category / reviewStatus），不是 MP 默认的下划线风格，
        // 这里走的是字符串列名，写错了不会报错、只会静默查不到 —— 改完必须实测一次。
        List<Object> categoryRows = this.listObjs(new QueryWrapper<Image>()
                .select("DISTINCT category")
                .eq("reviewStatus", ImageConstant.REVIEW_STATUS_PASS));
        List<String> categories = categoryRows.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();
        vo.setCategories(categories);

        // 标签：一行存的是 JSON 数组字符串 ["风景","城市"]，DISTINCT 拆不开，只能取回来在内存里拆。
        // 图库量级（个人项目）下这个代价可以接受；真到百万级要换"标签表 + 关联表"的方案。
        List<Object> tagRows = this.listObjs(new QueryWrapper<Image>()
                .select("tags")
                .eq("reviewStatus", ImageConstant.REVIEW_STATUS_PASS));
        java.util.TreeSet<String> tagSet = new java.util.TreeSet<>();
        for (Object row : tagRows) {
            if (row == null) {
                continue;
            }
            // 宽松解析：历史数据里可能有裸字符串（编辑弹窗早期是纯文本框，填什么存什么），
            // 严格按 JSON 解析会把它们整条丢掉 —— 表现就是"标签填了但筛选栏里没有"
            tagSet.addAll(parseTagsLenient(row.toString()));
        }
        vo.setTags(new ArrayList<>(tagSet));
        return vo;
    }

    public ImageVO toImageVO(Image image) {
        if (image == null) {
            return null;
        }
        ImageVO imageVO = new ImageVO();
        // 雪花 id 一律字符串化：19 位 Long 直出给 JS 会丢精度（末尾几位变 0）
        imageVO.setId(String.valueOf(image.getId()));
        imageVO.setReviewStatus(image.getReviewStatus());
        imageVO.setReviewMessage(image.getReviewMessage());
        imageVO.setUrl(image.getUrl());
        // thumbnailUrl 是推导出来的，不是表里的列 —— 库里只存原图地址这一个"事实"，
        // 派生信息在使用时现算，两处数据永远不会对不上
        imageVO.setThumbnailUrl(cosManager != null
                ? cosManager.buildThumbnailUrl(image.getUrl()) : null);
        imageVO.setWebpUrl(cosManager != null
                ? cosManager.buildWebpUrl(image.getUrl()) : null);
        imageVO.setName(image.getName());
        imageVO.setIntroduction(image.getIntroduction());
        imageVO.setCategory(image.getCategory());
        imageVO.setTags(image.getTags());
        imageVO.setPicSize(image.getPicSize());
        imageVO.setPicWidth(image.getPicWidth());
        imageVO.setPicHeight(image.getPicHeight());
        imageVO.setPicScale(image.getPicScale());
        imageVO.setPicColor(image.getPicColor());
        imageVO.setPicFormat(image.getPicFormat());
        imageVO.setUserId(String.valueOf(image.getUserId()));
        imageVO.setCreateTime(image.getCreateTime());
        return imageVO;
    }

    // ==================== 权限相关（本模块的重点） ====================

    /**
     * 审核图片（管理员）。
     *
     * <p>状态机：待审核(0) → 通过(1) / 拒绝(2)。允许「拒绝后再改成通过」
     * （用户申诉改了图、管理员复查），不允许任何状态回到"待审核"——
     * 审核是一次有记录的动作，"撤销成没审过"等于抹掉审核痕迹。
     *
     * <p>审核是"标注 + 记录"，不改图的内容与归属 —— 所以只 set 审核四字段，
     * 不碰 name / url / userId。
     */
    @Override
    public boolean reviewImage(String id, Integer reviewStatus, String reviewMessage, Boolean resetReviewParams) {
        userService.checkAdminUser();
        if (reviewStatus == null
                || (reviewStatus != ImageConstant.REVIEW_STATUS_PASS
                && reviewStatus != ImageConstant.REVIEW_STATUS_REJECT)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "审核状态只能是 通过/拒绝");
        }
        // null 按「重置」处理：不传这个字段的老客户端，行为必须跟以前完全一致，
        // 否则一次接口升级就会静默变成「只改状态」，审核人/时间/意见全停在上一版
        boolean reset = resetReviewParams == null || resetReviewParams;
        // 「拒绝必填原因」只在会写入意见时才要求：选「仅重置状态」时这条意见不落库，
        // 强行要原因等于让用户填一个会被丢掉的值
        if (reset && reviewStatus == ImageConstant.REVIEW_STATUS_REJECT
                && ParamUtils.isBlank(reviewMessage)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "拒绝时必须填写原因");
        }

        long imageId = ParamUtils.parseId(id);
        Image image = this.getById(imageId);
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }

        User reviewer = userService.getLoginUser();
        // 用 UpdateWrapper 而不是 updateById(entity)：审核意见要能被显式写成 null
        // （通过时不留意见），而 MP 的 NOT_NULL 更新策略会跳过 null 字段，做不到「清空」。
        LambdaUpdateWrapper<Image> wrapper = new LambdaUpdateWrapper<Image>()
                .eq(Image::getId, imageId)
                .set(Image::getReviewStatus, reviewStatus);

        if (reset) {
            // 重置全部审核参数：审核人 / 时间 / 意见 全部以本次为准
            wrapper.set(Image::getReviewerId, reviewer.getId())
                    .set(Image::getReviewTime, new Date())
                    .set(Image::getReviewMessage,
                            reviewStatus == ImageConstant.REVIEW_STATUS_REJECT
                                    ? reviewMessage.trim() : null);
        }
        // 不重置时只改状态：上一次的审核人 / 时间 / 意见留在库里当历史参考
        // （前端不展示它们，但管理员需要时能查到「上次是谁、为什么」）

        boolean result = this.update(wrapper);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "审核失败");
        }
        log.info("图片审核完成：imageId={}, result={}, reviewerId={}, resetParams={}",
                imageId, reviewStatus == ImageConstant.REVIEW_STATUS_PASS ? "通过" : "拒绝",
                reviewer.getId(), reset);
        return true;
    }

    @Override
    public ImageDownloadResult downloadImage(String id) {
        User loginUser = userService.getLoginUser();
        Image image = this.getById(ParamUtils.parseId(id));
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        // 与详情同一套可见性：能看就能下载（主页图墙里别人传的已通过图，也能下载）
        checkImageVisible(image, loginUser);

        if (cosManager == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "对象存储未配置，无法下载");
        }
        String key = cosManager.getKeyFromUrl(image.getUrl());
        byte[] bytes = cosManager.download(key);

        // 保存文件名：图片名称 + 真实后缀。名称里的路径分隔符要清掉
        // （用户把图命名为 "a/b.jpg" 时，浏览器的"另存为"会被带进子目录）
        String safeName = image.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeName + "." + image.getPicFormat();

        // Content-Type：按存的格式映射（picFormat 是上传时魔数校验过的白名单值，不会跑偏）
        String contentType = switch (image.getPicFormat()) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        };
        return new ImageDownloadResult(bytes, fileName, contentType);
    }

    /**
     * 标签筛选的落点：单标签模糊查，JSON 数组则逐个 like（AND 语义）。
     *
     * <p>为什么用 {@code like '%"标签"%'}（带引号）而不是裸 {@code like '%标签%'}：
     * tags 存的是 JSON 数组字符串 {@code ["风景","城市"]}。查 {@code 景} 时裸匹配
     * 能命中，但查 {@code 城市} 时，一张标签为 {@code ["城市地标"]} 的图也会被
     * 「子串误命中」—— 加上 JSON 的引号定界后，匹配的是完整的
     * {@code "城市"} 元素，误报率大幅下降。仍是模糊方案（够用、零依赖），
     * 要完全精准得上 MySQL JSON 函数，将来有需要再升级。
     *
     * <p>放在 private 方法而不是内联在查询链里：这段逻辑有「解析 + 分流 + 校验」
     * 二十来行，内联会把本来一目了然的查询链搅乱。
     */
    /**
     * 把前端传来的标签规范化成**库里唯一的格式**：JSON 数组字符串。
     *
     * <p><b>为什么必须规范化（这是实测踩出来的坑）：</b>编辑弹窗原来是个纯文本框，
     * 用户填「测试」就原样落库成 {@code 测试}（不是 {@code ["测试"]}）。
     * 后果是下游全乱：筛选项接口按 JSON 解析会失败（标签在主页那条标签栏里直接消失）、
     * 详情页 {@code JSON.parse} 抛异常（标签不显示）。<b>契约写在注释里、没人守，
     * 就等于没有契约</b> —— 所以守在这里：Service 是唯一写入口。
     *
     * <p>接收三种写法（对用户宽容，对库严格）：
     * <ul>
     *   <li>{@code ["风景","城市"]} —— 已经是规范格式，校验后原样用</li>
     *   <li>{@code 风景,城市} / {@code 风景，城市} —— 逗号（中英文都认）分隔，转成数组</li>
     *   <li>{@code 风景} —— 单个标签</li>
     * </ul>
     *
     * <p>null 与空串的语义<b>不同</b>：null = 不传 = 不改（靠 MP 的 NOT_NULL 策略跳过），
     * 空串 = 明确要清空 → 存成 {@code []}。两者混起来做会丢掉"清空标签"这个能力。
     */
    private String normalizeTags(String rawTags) {
        if (rawTags == null) {
            return null;
        }
        String trimmed = rawTags.trim();
        if (trimmed.isEmpty()) {
            return "[]";
        }
        // 已经是 JSON 数组：校验后原样保留（失败就报错，不静默吞掉用户输入）
        if (trimmed.startsWith("[")) {
            List<String> parsed = parseTagsAsJson(trimmed);
            if (parsed == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "标签格式不合法（应为 JSON 数组或逗号分隔）");
            }
            return toTagsJson(parsed);
        }
        // 逗号分隔 / 单个标签：切分、去空白、去重
        List<String> tags = new ArrayList<>();
        for (String part : trimmed.split("[,，]")) {
            String tag = part.trim();
            if (!tag.isEmpty() && !tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return toTagsJson(tags);
    }

    /** 宽松解析：JSON 数组，或（历史脏数据）裸字符串 / 逗号分隔。解析不出返回空列表 */
    private List<String> parseTagsLenient(String rawTags) {
        if (rawTags == null) {
            return List.of();
        }
        String trimmed = rawTags.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (trimmed.startsWith("[")) {
            List<String> parsed = parseTagsAsJson(trimmed);
            if (parsed != null) {
                return parsed;
            }
            // JSON 坏了也不丢数据：退化成"整串当一个标签"，至少别让它从筛选项里消失
            log.warn("标签 JSON 解析失败，按单标签处理：tags={}", trimmed);
        }
        List<String> tags = new ArrayList<>();
        for (String part : trimmed.split("[,，]")) {
            // 去掉可能残留的 JSON 符号（引号 / 方括号）：处理"半截 JSON"这类脏值。
            // 用连续 replace 而不是正则字符类 —— 字符类里塞引号要写成 \" 的形式，
            // 在 Java 字符串里极易写成"字符串提前结束"，编译期报"非法字符"看不出根因
            String tag = part.trim().replace("[", "").replace("]", "").replace("\"", "").trim();
            if (!tag.isEmpty() && !tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /** 严格解析 JSON 数组：不是数组、元素非字符串、含空标签都返回 null（交给调用方报错） */
    private List<String> parseTagsAsJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return null;
            }
            List<String> tags = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isTextual()) {
                    return null;
                }
                String tag = item.asText().trim();
                if (tag.isEmpty()) {
                    return null;
                }
                if (!tags.contains(tag)) {
                    tags.add(tag);
                }
            }
            return tags;
        } catch (Exception e) {
            return null;
        }
    }

    private String toTagsJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            // 序列化 List<String> 不会失败；真失败说明环境有问题，不该静默写脏数据
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "标签保存失败");
        }
    }

    /**
     * 颜色筛选：入参是色板标准色，归类成色系后按 {@code picColorFamily} 等值匹配。
     *
     * <p><b>为什么不直接比色值：</b>主色是连续空间里的具体值（#FDF9FB「近似白」），
     * 色板是离散标准色（#FFFFFF）——等值比较两个空间几乎永远对不上，用户点「白色」
     * 一张都筛不出来。色系是把连续值收敛到离散类别的桥：每张图恰好属于一个色系，
     * 点任何色块都有确定结果（不会出现「点了却什么都没有」）。
     *
     * <p>格式不对报 40000 而不是静默忽略：用户明确选了颜色却搜出全部图，
     * 等于筛选失效还不报错（与标签数组的处理同一立场）。
     */
    private void applyColorFilter(LambdaQueryWrapper<Image> wrapper, String picColor) {
        String family = ImageUtils.classifyColorFamily(picColor);
        if (family == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "颜色格式不正确，应为 #RRGGBB");
        }
        wrapper.eq(Image::getPicColorFamily, family);
    }

    private void applyTagsFilter(LambdaQueryWrapper<Image> wrapper, String tags) {
        if (ParamUtils.isBlank(tags)) {
            return;
        }
        String trimmed = tags.trim();
        if (trimmed.startsWith("[")) {
            // 多选：解析成 JSON 数组，每个标签独立 like，全部命中才算
            List<String> tagList;
            try {
                // 用注入的共享 ObjectMapper（线程安全、带缓存），别每处 new 一个
                tagList = objectMapper.readValue(trimmed,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception e) {
                // 数组解析失败：是"看起来像 JSON 其实不是"的垃圾输入 → 报参数错误，
                // 而不是静默降级成模糊查（用户以为是多选，结果按整串模糊，行为不可预期）
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "标签格式不正确");
            }
            for (String tag : tagList) {
                if (ParamUtils.isBlank(tag)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "标签不能为空");
                }
                wrapper.like(Image::getTags, "\"" + tag.trim() + "\"");
            }
        } else {
            // 单标签：保持原有模糊匹配语义
            wrapper.like(Image::getTags, trimmed);
        }
    }

    /**
     * 归属校验：这张图是不是当前登录用户的？管理员豁免。
     *
     * <p>放在 Service 层而不是 Controller：将来多一个入口（定时任务、小程序、开放 API）
     * 照样受这个校验保护。Controller 的校验是"这一条路"的保护，Service 的校验是"所有路"的保护。
     *
     * @param image     要操作的图片（已确认存在）
     * @param loginUser 当前登录用户
     * @throws BusinessException 无权操作他人的图片（40101）
     */
    private void checkImageOwner(Image image, User loginUser) {
        if (isAdmin(loginUser)) {
            return;
        }
        // ⚠️ Long 必须用 equals 比较，不能用 ==
        //    == 比的是引用地址：-128~127 之外，两个数值相同的 Long 对象也可能"不相等"。
        //    雪花 id 是 19 位，远超这个范围 —— 用 == 会随机判错，而且可能只在生产上偶发。
        if (!loginUser.getId().equals(image.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权操作他人的图片");
        }
    }

    /**
     * 把"这次查询能看谁的数据"落到 SQL 的 WHERE 上。
     *
     * <p><b>查询接口同样能越权。</b>有人以为"只有写操作要校验权限"，但能看见别人的数据
     * 本身就是泄露 —— 尤其是图库这种以"私有图片"为核心的产品。
     *
     * <p>关键点：<b>普通用户的 userId 入参一律被忽略</b>，强制改用自己的 id。
     * 如果写成"传了 userId 就用传的"，前端传个别人的 id 就能看光别人的图。
     */
    private void applyUserScope(LambdaQueryWrapper<Image> wrapper, ImageQueryRequest request, User loginUser) {
        if (isAdmin(loginUser)) {
            // 管理员：默认查全部；传了 userId 就只看那个人的；审核状态按入参筛（不强制）
            if (!ParamUtils.isBlank(request.getUserId())) {
                wrapper.eq(Image::getUserId, ParamUtils.parseId(request.getUserId()));
            }
            return;
        }

        // ========== 普通用户两种视角（官方流程的「内容可见性」） ==========
        // ① 我的图片（显式传自己的 userId）：看自己的**全部状态** ——
        //    否则刚上传的图立刻"消失"、被拒的图连原因都看不到，用户根本没法申诉
        // ② 浏览（不传 userId，或传的是别人的）：全站的**已通过**图，即主页图墙
        //    别人传的 userId 一律当"浏览"处理，不报错也不越权（越权探测不值得给提示）
        boolean mine = !ParamUtils.isBlank(request.getUserId())
                && loginUser.getId().equals(ParamUtils.parseId(request.getUserId()));
        if (mine) {
            wrapper.eq(Image::getUserId, loginUser.getId());
            if (request.getReviewStatus() != null) {
                wrapper.eq(Image::getReviewStatus, request.getReviewStatus());
            }
        } else {
            // 浏览视角下状态由服务端钉死，入参传什么都只看已通过
            wrapper.eq(Image::getReviewStatus, ImageConstant.REVIEW_STATUS_PASS);
        }
    }

    /**
     * 单张图片的可见性校验（详情 / 下载这类「按 id 取一张」的入口用）。
     *
     * <p>规则：本人看自己的全部状态；管理员看全部；<b>其他人只能看已通过的</b>。
     * 与列表的 {@link #applyUserScope} 同一套语义 —— 列表上看不见的图，
     * 直接把 id 拼到 URL 上也不该看得见，否则「可见性」只是前端不显示而已。
     */
    private void checkImageVisible(Image image, User loginUser) {
        if (isAdmin(loginUser)) {
            return;
        }
        boolean mine = loginUser.getId().equals(image.getUserId());
        if (mine) {
            return;
        }
        boolean passed = Integer.valueOf(ImageConstant.REVIEW_STATUS_PASS).equals(image.getReviewStatus());
        if (!passed) {
            // 报「不存在」而不是「无权限」：别人无权知道的图，连"存在"这件事都不该暴露
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
    }

    /**
     * 是否为管理员。
     *
     * <p>常量写在左边（{@code ADMIN_ROLE.equals(...)}）：userRole 为 null 时
     * {@code null.equals(...)} 会 NPE，反过来调用则是安全的返回 false。
     *
     * <p>与 {@code UserServiceImpl.checkAdminUser()} 的区别：那个是"校验即抛异常"，
     * 用于"只有管理员能进"的接口；这个是纯粹的判断，用于"本人 or 管理员"这类组合条件。
     */
    private static boolean isAdmin(User user) {
        return UserConstant.ADMIN_ROLE.equals(user.getUserRole());
    }
}
