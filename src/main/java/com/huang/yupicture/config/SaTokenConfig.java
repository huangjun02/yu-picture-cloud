package com.huang.yupicture.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 全局鉴权配置：默认所有接口都要登录。
 *
 * <p><b>为什么用拦截器而不是每个接口手动调 getLoginUser()：</b>
 * 手写校验是"默认不安全"——新加一个接口忘了写就是敞开的。
 * 拦截器把它反过来：默认全部拦截，不需要登录的接口显式列进白名单。
 * 漏写白名单最多是"自己人访问被拒"（一测就发现），不会变成越权漏洞。
 *
 * <p><b>⚠️ 路径不含 context-path：</b>application.yml 配了 context-path=/api，
 * 浏览器 URL 是 /api/user/login，但 MVC 拦截器看到的是剥掉前缀后的 /user/login。
 * 白名单误写成 /api/... 会静默不生效（表现为"连登录接口都被拦"）。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /** 免登录白名单（注意：不带 /api 前缀） */
    private static final String[] WHITE_LIST = {
            // 登录、注册：未登录的人也得能访问，否则是死锁
            "/user/login",
            "/user/register",
            // 健康检查
            "/health",
            // knife4j / springdoc 的文档页与静态资源 —— 不放行则接口文档打不开
            // knife4j 的 doc.html 用【相对路径】引用资源（webjars/css/...），
            // 所以实际请求是 /api/webjars/** —— 剥掉 context-path 后正是下面这几条。
            // doc.html 自身也带一句提示：「确保 knife4j 静态资源被放行，别被安全框架拦截」。
            "/doc.html",
            "/webjars/**",
            // knife4j 的图标资源（favicon 等）在 /img/** 下，拦了页面主体仍可用，但控制台会刷 40100
            "/img/**",
            "/v3/api-docs/**",
            // ⚠️ 必须单独写这一条：/swagger-ui/** 匹配不到 /swagger-ui.html
            //（Ant 的 /** 要求「/swagger-ui/」前缀，而 .html 是独立路径，不是目录下的文件）
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/swagger-resources/**",
            "/favicon.ico",
            // Spring Boot 的错误转发路径，避免错误页自己被拦成 40100
            "/error"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // lambda 里的 handle 用不到（它是被拦截的 handler 对象）；
        // 每次请求命中拦截路径，就执行这句「没登录就抛 NotLoginException」
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(WHITE_LIST);
    }
}