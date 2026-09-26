package com.huang.yupicture.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.huang.yupicture.mapper.UserMapper;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.service.UserService;
import org.springframework.stereotype.Service;

/**
 * 用户业务实现。
 *
 * <p>{@code ServiceImpl<Mapper, Entity>} 把 Mapper 的 CRUD 包装成业务方法，
 * 并通过构造函数注入 {@link UserMapper}（泛型里的 Mapper 类型就是注入线索）。
 *
 * <p>现在还是空的 —— 第 3 期写注册/登录时才往这里加真正的业务逻辑。
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
}
