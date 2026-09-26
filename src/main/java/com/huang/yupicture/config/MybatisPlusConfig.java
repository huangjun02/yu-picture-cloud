package com.huang.yupicture.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>两件事：
 * <ol>
 *   <li>{@code @MapperScan}：告诉 MyBatis 去哪个包找 Mapper 接口，
 *       省得每个接口都写 {@code @Mapper}（写漏一个就是启动期 bean 找不到）。</li>
 *   <li>分页插件：<b>不加它不会报错</b>，{@code Page} 查询会静默返回全表数据
 *       —— 这是 MyBatis-Plus 最阴的一个坑，所以必须显式注册。</li>
 * </ol>
 */
@Configuration
@MapperScan("com.huang.yupicture.mapper")
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 拦截器链。
     * <p>分页、乐观锁、防全表更新等插件都挂在同一条链上，顺序按需排列（分页一般放最后）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 显式指定 DbType.MYSQL：交给它自动识别也能跑，但以后接了多数据源容易判错方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
