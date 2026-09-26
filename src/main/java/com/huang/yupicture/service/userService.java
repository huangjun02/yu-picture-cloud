package com.huang.yupicture.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.huang.yupicture.model.entity.User;

/**
 * 用户业务接口。
 *
 * <p>继承 {@link IService} 拿到 MP 的通用业务方法（save / getById / page / removeById ...）。
 *
 * <p>⚠️ 包路径注意：MyBatis-Plus <b>3.5.17 起把 IService 从
 * {@code com.baomidou.mybatisplus.extension.service} 迁到了
 * {@code com.baomidou.mybatisplus.spring.service}</b>。
 * 网上教程（含课程）写的旧路径在本项目上编译不过 —— 手写时用 IDE 补全，别背路径。
 */
public interface UserService extends IService<User> {
}
