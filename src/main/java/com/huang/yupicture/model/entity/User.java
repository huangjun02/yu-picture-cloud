package com.huang.yupicture.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户实体，对应表 user。
 *
 * <p>字段名一律用 Java 驼峰（userAccount），跟数据库列名（userAccount）逐字一致。
 * 靠"MySQL 列名不区分大小写"来兜底是危险的：换 H2 / PostgreSQL 就会挂。
 */
@TableName("user")
@Data
public class User implements Serializable {

    /**
     * id —— ASSIGN_ID：由 MyBatis-Plus 用雪花算法生成 19 位 long，不依赖数据库自增。
     *
     * <p>取舍：
     * <ul>
     *   <li>优点：分布式唯一；不暴露业务量（自增 id 能被外人推算出"你有多少用户"）；便于分库/迁移</li>
     *   <li>代价：19 位 &gt; JS 的 {@code Number.MAX_SAFE_INTEGER}（2^53-1），
     *       <b>返回给前端时必须序列化成字符串</b>，否则 JS 静默丢精度（第 3 期 LoginUserVO 里处理）</li>
     *   <li>连带：DDL 里那个 AUTO_INCREMENT 就此多余（显式插值会把计数器顶高），去掉更干净，留着也无害</li>
     * </ul>
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 账号 */
    private String userAccount;

    /** 密码（加密后的密文，绝不存明文） */
    private String userPassword;

    /** 用户昵称 */
    private String userName;

    /** 用户头像（图片 URL，可能很长） */
    private String userAvatar;

    /** 用户简介 */
    private String userProfile;

    /** 用户角色：user / admin */
    private String userRole;

    /** 创建时间（DDL 默认 CURRENT_TIMESTAMP 兜底） */
    private Date createTime;

    /** 更新时间（DDL 的 ON UPDATE 自动刷新） */
    private Date updateTime;

    /**
     * 是否删除：0-正常，1-已删除。
     * 显式 @TableLogic 标明这是逻辑删除字段（application.yml 里也全局配了 logic-delete-field，双保险）。
     */
    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
