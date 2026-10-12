package com.huang.yupicture.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据抓取（批量抓图）配置。
 *
 * <p><b>为什么抓取源是 Openverse 而不是 Bing：</b>Bing 的 {@code robots.txt} 里
 * 明文写着 {@code Disallow: /images/search?} —— 抓它的图片搜索页是站点明确禁止的。
 * 一个"功能"不该建立在被禁的路径上（自己临时跑一次脚本是另一回事，那是个人选择，
 * 但写进产品代码就等于替所有使用者做了这个决定）。
 *
 * <p>Openverse（openverse.org）是 CC 授权内容的聚合检索，公开 API、返回许可与作者信息，
 * 匿名配额 20 次/分钟 + 200 次/天 —— 一次请求可带回 20 张图，够用且对源站友好。
 *
 * <p><b>代理为什么是配置项：</b>本机直连 Openverse 返回 000（不通），走 Clash
 * （127.0.0.1:7892）才通。代理写死会把"这台机器的网络状况"变成产品的隐含前提 ——
 * 留空即为直连，谁的环境需要谁配。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yupicture.crawl")
public class CrawlProperties {

    /** 检索接口地址（Openverse 图片检索） */
    private String searchUrl = "https://api.openverse.org/v1/images/";

    /** HTTP 代理，留空 = 直连。本机需要 http://127.0.0.1:7892（Clash） */
    private String proxy = "";

    /** 单次请求超时（毫秒） */
    private int timeoutMs = 20000;

    /** 每次抓取最多几张（前端传的 count 会被收敛到这个上限） */
    private int maxCount = 20;

    /** 一次检索向源站索取的条数（多取一些，因为有的会下载失败 / 重复） */
    private int fetchSize = 20;

    /** 声明身份的 User-Agent —— 合规抓取不伪装浏览器 */
    private String userAgent = "yu-picture-cloud/1.0 (learning project)";

    /** 每张图之间的间隔（毫秒）：抓别人的图床，发太快是给源站添麻烦，也是被封的直接原因 */
    private long intervalMs = 300;

    /** 详情页/入库时写入的简介前缀里是否带来源说明（CC 授权要求署名，默认带） */
    private boolean writeAttribution = true;
}
