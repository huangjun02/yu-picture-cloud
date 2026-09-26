package com.huang.yupicture.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huang.yupicture.module.entity.user;
import com.huang.yupicture.service.userService;
import com.huang.yupicture.mapper.userMapper;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【user(用户)】的数据库操作Service实现
* @createDate 2026-09-26 23:59:30
*/
@Service
public class userServiceImpl extends ServiceImpl<userMapper, user>
    implements userService{

}




