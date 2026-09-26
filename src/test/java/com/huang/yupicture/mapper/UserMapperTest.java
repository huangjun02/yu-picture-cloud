package com.huang.yupicture.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.huang.yupicture.model.entity.User;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserMapper 集成测试 —— 每条断言都在验一个具体机制，不是凑数。
 *
 * <p>@Transactional：每条测试跑完自动回滚，不在库里留脏数据。
 * （@SpringBootTest 会真连 MySQL，因为 active profile = local）
 */
@SpringBootTest
@Transactional
class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    private User newUser(String account) {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword("encrypted-dummy");
        user.setUserName("昵称-" + account);
        return user;
    }

    @Test
    @DisplayName("insert 应该回填雪花 id（验 IdType.ASSIGN_ID）")
    void insert_shouldBackfillSnowflakeId() {
        User user = newUser("t_insert");
        int rows = userMapper.insert(user);

        Assertions.assertEquals(1, rows);
        Assertions.assertNotNull(user.getId(), "id 没有被回填 → IdType 配置不对");
        Assertions.assertTrue(user.getId() >= 100_000_000_000_000_000L,
                "id 应该是由 MP 生成的 19 位雪花号：" + user.getId());
    }

    @Test
    @DisplayName("selectById 应该正确映射驼峰字段（验列名映射）")
    void selectById_shouldMapCamelCaseFields() {
        User user = newUser("t_select");
        userMapper.insert(user);

        User loaded = userMapper.selectById(user.getId());

        Assertions.assertNotNull(loaded);
        Assertions.assertEquals("t_select", loaded.getUserAccount());
        Assertions.assertEquals("昵称-t_select", loaded.getUserName());
        Assertions.assertEquals("user", loaded.getUserRole(), "DDL 的 DEFAULT 'user' 没生效");
        Assertions.assertNotNull(loaded.getCreateTime(), "DDL 的 DEFAULT CURRENT_TIMESTAMP 没生效");
        Assertions.assertEquals(0, loaded.getIsDelete());
    }

    @Test
    @DisplayName("同账号重复插入应该被唯一索引拦住（验 DDL 的 uk_userAccount）")
    void insert_duplicateAccount_shouldThrow() {
        userMapper.insert(newUser("t_dup"));

        Assertions.assertThrows(DuplicateKeyException.class,
                () -> userMapper.insert(newUser("t_dup")),
                "唯一索引没生效 → 检查 sql/02 里的 uk_userAccount");
    }

    @Test
    @DisplayName("deleteById 应该是逻辑删除（验 @TableLogic）")
    void deleteById_shouldBeLogicDelete() {
        User user = newUser("t_del");
        userMapper.insert(user);

        int rows = userMapper.deleteById(user.getId());

        Assertions.assertEquals(1, rows);
        Assertions.assertNull(userMapper.selectById(user.getId()),
                "逻辑删除后还能查出来 → @TableLogic / 全局配置有问题");
    }

    @Test
    @DisplayName("selectPage 应该真分页（验分页插件）")
    void selectPage_shouldReturnPagedResult() {
        for (int i = 1; i <= 3; i++) {
            userMapper.insert(newUser("t_page_" + i));
        }

        // ⚠️ 这里必须带过滤条件。不带条件时统计的是「全表行数」，
        // 库里只要存在任何别的数据（比如本地联调用的种子用户 huangjun），
        // total 就不再是 3 —— 测试会莫名其妙失败。测试只该认自己造的数据。
        Page<User> page = userMapper.selectPage(new Page<>(1, 2),
                new LambdaQueryWrapper<User>().likeRight(User::getUserAccount, "t_page_"));

        Assertions.assertEquals(3, page.getTotal(), "total 不对 → 分页插件没生效");
        Assertions.assertEquals(2, page.getRecords().size(), "records 不对 → 分页插件没生效");
    }
}
