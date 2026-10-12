package com.huang.yupicture.manager;

import com.huang.yupicture.config.NetworkProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 对外 HTTP 抓取用的共享 HttpClient。
 *
 * <p><b>为什么要收口：</b>应用里有两处主动访问外网 —— URL 传图（拉远程图片）和数据抓取
 * （检索 + 下载）。两处各建一个 HttpClient 的话，"要不要走代理"这个环境事实就散落在
 * 两个地方，改一处漏一处。这里统一构建，调用方只管设自己的请求超时。
 *
 * <p><b>HttpClient 是线程安全且可复用的</b>：它内部维护连接池，每次新建会丢掉连接复用
 * 的收益，所以构建一次、缓存起来。
 *
 * <p>代理配了但解析不出来时**不静默降级成直连**：直连失败的现象是"超时"，
 * 用户完全看不出是代理配错了 —— 那种错误最难查。直接抛，启动即暴露。
 */
@Slf4j
@Component
public class OutboundHttpClient {

    @Resource
    private NetworkProperties networkProperties;

    private volatile HttpClient cached;

    /** 取共享客户端（首次调用时构建，之后复用） */
    public HttpClient get() {
        HttpClient local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = build();
                    cached = local;
                }
            }
        }
        return local;
    }

    private HttpClient build() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // 跟 301/302（图床常重定向），但只跟同协议：NORMAL 不跟 https → http 降级
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = networkProperties.getProxy();
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI uri = URI.create(proxy.trim());
                String host = uri.getHost();
                int port = uri.getPort();
                if (host == null || port <= 0) {
                    throw new IllegalArgumentException("代理地址要带主机和端口，例如 http://127.0.0.1:7892");
                }
                builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
                log.info("出站 HTTP 走代理：{}:{}", host, port);
            } catch (Exception e) {
                throw new IllegalStateException("yupicture.network.proxy 配置不合法：" + proxy, e);
            }
        }
        return builder.build();
    }
}
