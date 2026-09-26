package com.huang.yupicture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huang.yupicture.model.entity.User;

/**
 * 用户表(user)的数据库操作 Mapper。
 *
 * <p>继承 {@link BaseMapper} 就拿到全套单表 CRUD（insert / selectById / selectPage / deleteById ...），
 * 一行方法都不用写；只有多表关联或复杂语句才需要自己加方法 + 配套 XML。
 *
 * <p>被 {@code MybatisPlusConfig} 上的 {@code @MapperScan("com.huang.yupicture.mapper")} 扫到，
 * 所以不需要再加 {@code @Mapper} 注解。
 */
public interface UserMapper extends BaseMapper<User> {
}
