package com.huang.yupicture.utils;

import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 远程图片 URL 的校验与拉取（URL 传图专用）。
 *
 * <p><b>为什么 URL 传图比文件上传危险：</b>文件上传时，攻击者能控制的是"文件内容"；
 * URL 传图时，他还控制了"<b>服务器去哪里发请求</b>"。后者是更严重的一类漏洞 ——
 * SSRF（Server-Side Request Forgery，服务端请求伪造）：诱导你的后端去请求
 * 它本不该访问的地址。
 *
 * <p><b>SSRF 能干什么（不是理论风险）：</b>
 * <ul>
 *   <li>探测内网：{@code http://192.168.1.1/admin}、{@code http://10.0.0.5:8080} ——
 *       内网服务通常"默认只有内网能访问"，攻击者借你的服务器当跳板</li>
 *   <li>打云元数据：{@code http://169.254.169.254/latest/meta-data/} ——
 *       AWS/腾讯云等的主机都会在这个内网地址暴露临时凭据，拿到 = 整台机器失守</li>
 *   <li>回环打自己：{@code http://localhost:8123/image/delete} —— 你的管理接口
 *       只拦了登录态，服务端自己发的请求带着内网视角，可能绕过 IP 白名单类防线</li>
 * </ul>
 * 所以 URL 校验的目标是：<b>只允许公网 http(s) 地址</b>。
 *
 * <p><b>为什么不复用 {@link ImageUtils} 的魔数校验入口：</b>校验本身复用了
 * （{@code checkContent}），这里多做的只有"URL 形状校验 + 拉取"两步 ——
 * 与其改 ImageUtils 的签名，不如收在专门的工具类，职责清晰。
 */
public final class UrlPictureUtils {

    /** 只接受 http/https —— file: / ftp: / gopher: 等协议一概拒绝（file: 能读服务器本地文件） */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 大小上限与文件上传一致（5MB）—— 同一个业务，两入口一个标准 */
    public static final long MAX_URL_IMAGE_SIZE = ImageUtils.MAX_FILE_SIZE;

    private UrlPictureUtils() {
    }

    /**
     * 校验 URL 形状并返回规范化后的 URI。
     *
     * <p>只做「形状」检查（协议合法、主机存在、不是内网地址）；
     * 「内容是不是真图片」交给拉取后的魔数校验（{@link ImageUtils#checkContent}）——
     * 两道防线各管一段，顺序不能反：先花几微秒排除明显恶意的地址，
     * 再花几百毫秒去拉内容。
     *
     * @param rawUrl 用户输入的图片地址
     * @return 规范化后的 URI（后续拉取用）
     * @throws BusinessException 形状不合法 / 指向内网
     */
    public static URI checkUrl(String rawUrl) {
        if (ParamUtils.isBlank(rawUrl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址不能为空");
        }
        URI uri;
        try {
            // 先按 ASCII 编码解析：中文 URL（未经编码直接贴过来的）在这里会失败，
            // 那是用户输入不规范，报参数错误让他重新复制，而不是替他猜意图
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址格式不正确");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持 http/https 图片地址");
        }
        String host = uri.getHost();
        if (ParamUtils.isBlank(host)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址缺少主机名");
        }

        // 内网判定：解析出所有 IP，任何一个落在内网段都拒绝（DNS 可能解析出多个记录，
        // 只查第一个会被「域名指两个地址、一个公网一个内网」的混拼绕过）
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            // 解析失败 = 域名根本不存在，拉取必然失败，提前拒绝省一次网络往返
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址无法解析");
        }
        for (InetAddress address : addresses) {
            if (isPrivateAddress(address)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能使用内网地址");
            }
        }
        return uri;
    }

    /**
     * 是否内网地址。
     *
     * <p>用 JDK 自带的判断（isSiteLocalAddress = RFC1918 私有段）加两个它不覆盖的：
     * <ul>
     *   <li><b>回环</b>（127.0.0.1 / localhost）—— 打自己的服务</li>
     *   <li><b>link-local</b>（169.254.x.x）—— 云元数据服务都在这段，
     *       这一条是云上部署的保命检查</li>
     *   <li><b>anyLocal</b>（0.0.0.0）—— 语义上等于"所有网卡"</li>
     * </ul>
     */
    private static boolean isPrivateAddress(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress();
    }
}
