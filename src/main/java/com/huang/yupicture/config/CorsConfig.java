package com.huang.yupicture.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 全局跨域（CORS）配置。
 *
 * <p><b>先说清楚它解决什么问题</b>：开发阶段前端走 Vite 代理（5173 → 8123），
 * 浏览器看到的是同源请求，<b>压根不会触发 CORS</b>。
 * 这份配置是给「前端直连后端」和以后前后端分域部署用的，
 * 所以别把它当成 502 / 连不上的解药 —— 那类问题的病因通常是后端没起。
 *
 * <p>用 {@link CorsFilter} 而不是 {@code WebMvcConfigurer#addCorsMappings}：
 * Filter 在 DispatcherServlet 之前生效，连 404、静态资源、被拦截的请求也会带上 CORS 头。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 用 allowedOriginPatterns 而不是 allowedOrigins：
        // allowCredentials(true) 时 allowedOrigins 里不允许出现 "*"，否则启动即报错。
        // 这里干脆显式列出开发机的两个写法，不做通配。
        config.addAllowedOriginPattern("http://localhost:5173");
        config.addAllowedOriginPattern("http://127.0.0.1:5173");

        // 允许的请求头 / 方法：全放开。带鉴权的自定义头（如 satoken）也在里面
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        // 允许携带 Cookie —— Sa-Token 的登录态靠 cookie 传递，这一项必须为 true
        config.setAllowCredentials(true);

        // 预检请求（OPTIONS）的结果缓存 1 小时，省掉大量重复往返
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对本站所有路径生效
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
