package com.huang.yupicture.module.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * 用户
 * @TableName user
 */
@TableName(value ="user")
@Data
public class user {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 账号
     */
    private String useraccount;

    /**
     * 密码（加密后的密文，绝不存明文）
     */
    private String userpassword;

    /**
     * 用户昵称
     */
    private String username;

    /**
     * 用户头像（图片 URL，可能很长）
     */
    private String useravatar;

    /**
     * 用户简介
     */
    private String userprofile;

    /**
     * 用户角色：user / admin
     */
    private String userrole;

    /**
     * 创建时间
     */
    private Date createtime;

    /**
     * 更新时间
     */
    private Date updatetime;

    /**
     * 是否删除：0-正常，1-已删除（逻辑删除）
     */
    @TableLogic
    private Integer isdelete;
}