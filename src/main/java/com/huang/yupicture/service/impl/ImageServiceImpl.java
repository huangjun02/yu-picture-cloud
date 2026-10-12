package com.huang.yupicture.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.constant.ImageConstant;
import com.huang.yupicture.constant.UserConstant;
import com.huang.yupicture.manager.CosManager;
import com.huang.yupicture.mapper.ImageMapper;
import com.huang.yupicture.model.dto.image.ImageDownloadResult;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.ImageVO;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.ImageUtils;
import com.huang.yupicture.utils.ParamUtils;
import com.huang.yupicture.utils.UrlPictureUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Date;
import java.util.List;
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
        checkImageOwner(image, loginUser);
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
        image.setTags(imageUpdateRequest.getTags());

        boolean result = this.updateById(image);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新失败");
        }
        log.info("编辑图片：id={}, 操作人id={}", imageId, loginUser.getId());
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
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)  // 跟 301/302（图床常重定向），但只跟同协议公网（JDK 不跟 https→http 降级）
                    .build();
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
        image.setUserId(loginUser.getId());
        // 先过后审：任何入口传上来的图都从"待审核"开始（requirements 拍板：
        // 审核是事后管理不是准入门槛 —— 图照常可看可用，管理员有空再审）
        image.setReviewStatus(ImageConstant.REVIEW_STATUS_PENDING);

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
    public boolean reviewImage(String id, Integer reviewStatus, String reviewMessage) {
        userService.checkAdminUser();
        if (reviewStatus == null
                || (reviewStatus != ImageConstant.REVIEW_STATUS_PASS
                && reviewStatus != ImageConstant.REVIEW_STATUS_REJECT)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "审核状态只能是 通过/拒绝");
        }
        if (reviewStatus == ImageConstant.REVIEW_STATUS_REJECT
                && ParamUtils.isBlank(reviewMessage)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "拒绝时必须填写原因");
        }

        long imageId = ParamUtils.parseId(id);
        Image image = this.getById(imageId);
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }

        User reviewer = userService.getLoginUser();
        Image updateImage = new Image();
        updateImage.setId(image.getId());
        updateImage.setReviewStatus(reviewStatus);
        updateImage.setReviewMessage(ParamUtils.isBlank(reviewMessage) ? null : reviewMessage.trim());
        updateImage.setReviewerId(reviewer.getId());
        updateImage.setReviewTime(new Date());
        // 只 set 审核四字段（NOT_NULL 策略自动跳过 null）—— 审核动不了图的内容和归属
        boolean result = this.updateById(updateImage);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "审核失败");
        }
        log.info("图片审核完成：imageId={}, result={}, reviewerId={}",
                imageId, reviewStatus == ImageConstant.REVIEW_STATUS_PASS ? "通过" : "拒绝",
                reviewer.getId());
        return true;
    }

    @Override
    public ImageDownloadResult downloadImage(String id) {
        User loginUser = userService.getLoginUser();
        Image image = this.getById(ParamUtils.parseId(id));
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        // 与查询同一套归属校验：能看就能下载
        checkImageOwner(image, loginUser);

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
    private void applyTagsFilter(LambdaQueryWrapper<Image> wrapper, String tags) {
        if (ParamUtils.isBlank(tags)) {
            return;
        }
        String trimmed = tags.trim();
        if (trimmed.startsWith("[")) {
            // 多选：解析成 JSON 数组，每个标签独立 like，全部命中才算
            List<String> tagList;
            try {
                // Jackson 已在 classpath（Spring Boot 自带），直接用
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                tagList = mapper.readValue(trimmed,
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class));
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
            // 管理员：默认查全部；主动传了 userId 就只看那个人的
            if (!ParamUtils.isBlank(request.getUserId())) {
                wrapper.eq(Image::getUserId, ParamUtils.parseId(request.getUserId()));
            }
            return;
        }
        // 普通用户：强制只看自己的（注意这里不看 request.getUserId()）
        wrapper.eq(Image::getUserId, loginUser.getId());
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
