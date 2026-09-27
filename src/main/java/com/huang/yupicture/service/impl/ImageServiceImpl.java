package com.huang.yupicture.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.constant.UserConstant;
import com.huang.yupicture.manager.CosManager;
import com.huang.yupicture.mapper.ImageMapper;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.ImageVO;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.ImageUtils;
import com.huang.yupicture.utils.ParamUtils;
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
        if (!ParamUtils.isBlank(imageQueryRequest.getTags())) {
            wrapper.like(Image::getTags, imageQueryRequest.getTags());
        }
        if (!ParamUtils.isBlank(imageQueryRequest.getPicFormat())) {
            wrapper.eq(Image::getPicFormat, imageQueryRequest.getPicFormat());
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

        String imageName = resolveImageName(name, file.getOriginalFilename());
        if (imageName.length() > 256) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片名称不能超过 256 个字符");
        }

        // ========== 阶段二：上传对象存储 ==========
        // 存储可用性检查特意放在参数校验之后：参数不对是最常见的情况，应当先报 40000；
        // 顺序反了会让"传了个空文件"也报成"对象存储未配置"，把排查方向带偏。
        if (cosManager == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "对象存储未配置，无法上传（请在 application-local.yml 中配置 cos.client.*）");
        }
        String key = cosManager.upload(file, suffix);
        String url = cosManager.buildUrl(key);

        // ========== 阶段三：落库 ==========
        Image image = new Image();
        image.setUrl(url);
        image.setName(imageName);
        image.setPicSize(file.getSize());
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

        boolean result = this.save(image);
        if (!result) {
            // ⚠️ 补偿动作：数据库写失败，就把刚传上去的文件删掉。
            // 不删的话这个文件永远不会有记录指向它（"孤儿文件"），白占容量还一直计费，
            // 而且没有任何人会收到报错 —— 属于"不会被发现的错误"，必须主动清理。
            log.error("图片记录保存失败，回滚已上传的文件：key={}", key);
            try {
                cosManager.delete(key);
            } catch (Exception e) {
                // 回滚也失败：只能留日志给人工/定时任务兜底，但绝不能掩盖原始失败原因
                log.error("回滚删除 COS 文件失败，需人工清理：key={}", key, e);
            }
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "上传失败，请重试");
        }

        log.info("上传图片成功：imageId={}, userId={}, size={}字节",
                image.getId(), loginUser.getId(), file.getSize());
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
        imageVO.setUrl(image.getUrl());
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
