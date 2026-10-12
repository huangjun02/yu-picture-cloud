package com.huang.yupicture.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 出站网络配置（本应用主动访问外部 HTTP 服务时的统一开关）。
 *
 * <p><b>为什么需要它：</b>本机直连境外站点不通（commons.wikimedia.org 返回 000），
 * 走 Clash 代理（http://127.0.0.1:7892）才通。这个事实必须能配置，而不是写死在代码里 ——
 * 写死等于把"我这台机器装了 Clash"变成产品的隐含前提，换台机器整个功能就废了。
 *
 * <p>收口成一个键、一处构建 HttpClient：将来加超时策略、重试、限速都只改一个地方。
 * 留空 = 直连，不配代理的环境行为不变。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yupicture.network")
public class NetworkProperties {

    /** HTTP 代理，如 http://127.0.0.1:7892；留空 = 直连 */
    private String proxy = "";
}
