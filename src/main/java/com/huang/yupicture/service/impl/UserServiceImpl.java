package com.huang.yupicture.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.constant.UserConstant;
import com.huang.yupicture.mapper.UserMapper;
import com.huang.yupicture.model.dto.user.UserAddRequest;
import com.huang.yupicture.model.dto.user.UserQueryRequest;
import com.huang.yupicture.model.dto.user.UserRegisterRequest;
import com.huang.yupicture.model.dto.user.UserUpdateRequest;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.LoginUserVO;
import com.huang.yupicture.model.vo.UserVO;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.ParamUtils;
import com.huang.yupicture.utils.PasswordUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户业务实现。
 *
 * <p>{@code ServiceImpl<Mapper, Entity>} 把 Mapper 的 CRUD 包装成业务方法，
 * 并通过构造函数注入 {@link UserMapper}（泛型里的 Mapper 类型就是注入线索）。
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword) {
        // 1. 参数校验 —— 手写，不用 Bean Validation（本项目的既定选择）
        //    注意这里只校验"格式像不像"，不校验"长度够不够安全"，那属于注册时的密码强度规则
        if (userAccount == null || userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能少于 4 位");
        }
        if (userPassword == null || userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能少于 8 位");
        }

        // 2. 按账号查用户
        User user = getByAccount(userAccount);

        // 3. 比对密码
        //    ⚠️ "账号不存在" 和 "密码错误" 返回同一句文案 ——
        //    若分开提示，攻击者可以拿它当"账号是否存在"的探测器（用户枚举）。
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }
        if (!PasswordUtils.matches(userPassword, user.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }

        // 4. 记录登录态
        //    StpUtil.login(id) 会生成 token 并写入 cookie（token 名见 application.yml 的 sa-token.token-name）
        //    同时把登录信息存进 Redis（引入了 sa-token-redis-jackson）→ 重启后端登录态不丢
        StpUtil.login(user.getId());
        log.info("用户登录成功：id={}, account={}", user.getId(), user.getUserAccount());

        // 5. 返回脱敏信息（绝不把 userPassword 带出去）
        return toLoginUserVO(user);
    }

    @Override
    public User getLoginUser() {
        // isLogin() 内部会从 cookie/header 里解析 token；没有或已过期都是 false
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        long userId = StpUtil.getLoginIdAsLong();
        User user = this.getById(userId);
        if (user == null) {
            // 极端情况：登录态还在，但用户已被删（逻辑删除也算）
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }

    @Override
    public void userLogout() {
        // 幂等：未登录 / 会话已过期时调用也不报错（重复退出是正常操作，不该弹错误）
        StpUtil.logout();
    }

    /**
     * 按账号查一条用户（只会命中"未删除"的记录）。
     *
     * <p><b>为什么抽成一个方法：</b>userLogin、userRegister、userAdd 三处都要"按账号查一条"。
     * 复制三遍的代价不是多打几个字，而是将来加条件（比如"只查某个空间下的账号"）时要改三处，
     * 漏掉一处就是一个只在某条路径上出现的 bug。
     *
     * <p><b>为什么这里查不到已删除的账号：</b>{@code @TableLogic} 会让 MyBatis-Plus 自动补上
     * {@code AND isDelete = 0}。这正是我们要的 —— 配合 {@code uk_userAccount_isDelete} 复合唯一索引，
     * "账号被删掉之后可以重新注册"这条链路才成立（详见 sql/04_alter_user_unique_index.sql）。
     *
     * @param userAccount 账号（调用方保证非 null）
     * @return 用户实体；不存在时返回 null
     */
    private User getByAccount(String userAccount) {
        return this.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserAccount, userAccount));
    }

    /**
     * 实体 → 脱敏 VO。
     *
     * <p><b>为什么手写 set，而不用 {@code BeanUtils.copyProperties(user, vo)}：</b>
     * <ul>
     *   <li><b>id 这个字段它拷不过去</b>：实体是 {@code Long}、VO 是 {@code String}，
     *       反射拷贝碰到类型不兼容会直接跳过（字段留 null，不转换、不报错、不打日志）。
     *       实测：copyProperties 之后 {@code vo.getId() == null}，其余 6 个字段正常。
     *       也就是说该写的 {@code String.valueOf(...)} 一行都省不掉。</li>
     *   <li><b>脱敏边界必须写在明面上</b>：手写 set 显式声明「这个接口对外暴露哪些字段」；
     *       而 copyProperties 的语义是"同名就搬"—— 将来实体新增敏感字段（如 userPhone）、
     *       VO 恰好也有同名字段时，会被静默带出去，没有任何提示。
     *       今天 userPassword 没泄漏只是因为 VO 里没有这个字段，不能指望这种巧合。</li>
     * </ul>
     * <p>若将来字段多到值得上映射框架，用 MapStruct（编译期生成代码，类型不匹配直接编译报错），
     * 不要用运行期反射拷贝。
     */
    @Override
    public LoginUserVO toLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        // 雪花 id（19 位 long）必须字符串化，否则 JS 端丢精度 —— 详见 LoginUserVO#id 的注释
        loginUserVO.setId(String.valueOf(user.getId()));
        loginUserVO.setUserAccount(user.getUserAccount());
        loginUserVO.setUserName(user.getUserName());
        loginUserVO.setUserAvatar(user.getUserAvatar());
        loginUserVO.setUserProfile(user.getUserProfile());
        loginUserVO.setUserRole(user.getUserRole());
        loginUserVO.setCreateTime(user.getCreateTime());
        return loginUserVO;
    }

    @Override
    public String userRegister(UserRegisterRequest userRegisterRequest) {
        // 1. 参数校验（手写，项目不引 Bean Validation）
        if (userRegisterRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        if (userAccount == null || userPassword == null || checkPassword == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (userAccount.length() < 4 || userAccount.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度必须在 4 到 20 个字符之间");
        }
        // 字符白名单：只允许字母/数字/下划线。不加这条，空格、中文、emoji 都能进库，
        // 后续拼 URL、模糊搜索、前端展示都会出问题。
        if (!userAccount.matches("^[a-zA-Z0-9_]+$")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号格式不合法");
        }
        // ⚠️ 密码只设下限、不设上限：加上限会拒掉用户的强密码，而且与登录接口不对称
        //（登录只校验 ≥ 8），会出现"能登录却注册不了"的矛盾。
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能少于 8 位");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        // 2. 查重 —— 只是"友好提示"，真正的唯一性由数据库 uk_userAccount_isDelete 兜底：
        //    并发下两个请求会双双通过这一关，靠唯一索引挡住后一个（见下面的 catch）
        if (getByAccount(userAccount) != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }

        // 3. 加密 + 落库（明文到此为止，以下全是密文）
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(PasswordUtils.encrypt(userPassword));
        // 默认昵称取账号，之后允许用户自己改（可空字段）
        user.setUserName(userAccount);
        // 角色显式赋值：DDL 虽然写了 DEFAULT 'user'，但那条默认值生效的前提是
        // MyBatis-Plus 的 NOT_NULL 插入策略（null 字段不进 SQL）—— 依赖隐式行为不如写明意图
        user.setUserRole(UserConstant.DEFAULT_ROLE);

        boolean saveResult;
        try {
            saveResult = this.save(user);
        } catch (DuplicateKeyException e) {
            // 上面那次 getByAccount 只是"友好提示"：并发下两个同名注册会双双通过，
            // 第二个在这里撞唯一索引。把它翻译成业务语义 —— 否则会被全局处理器当成
            // "系统内部异常"(50000)，用户看到的是"服务器打瞌睡了"，排查也无从下手。
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败");
        }

        // 4. 返回 id 的字符串形式；注册后不自动登录（与 Controller 的约定一致）
        //    ⚠️ 日志只记 id 和账号，绝不记密码 —— 打印整个请求体会把明文密码写进日志文件
        log.info("用户注册成功：id={}, account={}", user.getId(), user.getUserAccount());
        return String.valueOf(user.getId());
    }

    @Override
    public void checkAdminUser() {
        // 复用 getLoginUser()：未登录由它抛 NOT_LOGIN_ERROR，这里只管"是不是管理员"
        User user = getLoginUser();
        // ⚠️ 常量在前：userRole 为 null 时，写成 user.getUserRole().equals(...) 会 NPE
        if (!UserConstant.ADMIN_ROLE.equals(user.getUserRole())) {
            // 抛 40101「无权限」而不是 40300「禁止访问」：语义是"已登录，但权限不够"
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 这里不额外记日志 —— 所有 BusinessException 都会被 GlobalExceptionHandler 统一记 warn
    }

    @Override
    public String userAdd(UserAddRequest userAddRequest) {
        // 1. 管理员身份 —— 放在最前面：越权请求不该再往下走任何一步业务逻辑
        checkAdminUser();

        // 2. 参数校验。账号规则与注册接口逐字一致 —— 两个入口建出来的账号必须守同一套规矩，
        //    否则会出现"注册接口建不了的号，管理员接口能建"这种自相矛盾
        if (userAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userAddRequest.getUserAccount();
        String userPassword = userAddRequest.getUserPassword();
        if (ParamUtils.isBlank(userAccount) || ParamUtils.isBlank(userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号和密码不能为空");
        }
        if (userAccount.length() < 4 || userAccount.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度必须在 4 到 20 个字符之间");
        }
        if (!userAccount.matches("^[a-zA-Z0-9_]+$")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号格式不合法");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能少于 8 位");
        }
        // 角色白名单：不加这条，前端能传 userRole="superadmin" 造出一个谁都不认识的脏角色；
        // 而 checkAdminUser() 只认 "admin"，那种账号既不是管理员、又过不了任何角色判断
        String userRole = userAddRequest.getUserRole();
        if (ParamUtils.isBlank(userRole)) {
            userRole = UserConstant.DEFAULT_ROLE;
        } else if (!UserConstant.DEFAULT_ROLE.equals(userRole) && !UserConstant.ADMIN_ROLE.equals(userRole)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "角色只能是 user 或 admin");
        }

        // 3. 查重（同一套兜底逻辑：先友好提示，唯一索引兜并发）
        if (getByAccount(userAccount) != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }

        // 4. 加密 + 落库
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(PasswordUtils.encrypt(userPassword));
        // 昵称不填就取账号，与注册的默认行为保持一致
        String userName = userAddRequest.getUserName();
        user.setUserName(ParamUtils.isBlank(userName) ? userAccount : userName);
        user.setUserAvatar(userAddRequest.getUserAvatar());
        user.setUserProfile(userAddRequest.getUserProfile());
        user.setUserRole(userRole);

        boolean saveResult;
        try {
            saveResult = this.save(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "新增用户失败");
        }

        // 记操作人：管理员接口的日志要能回答"这是谁干的"，只有 id 是答不上来的
        log.info("管理员新增用户：id={}, account={}, role={}, 操作人id={}",
                user.getId(), user.getUserAccount(), user.getUserRole(), StpUtil.getLoginIdAsLong());
        return String.valueOf(user.getId());
    }

    @Override
    public boolean userUpdate(UserUpdateRequest userUpdateRequest) {
        checkAdminUser();

        if (userUpdateRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long userId = ParamUtils.parseId(userUpdateRequest.getId());
        if (this.getById(userId) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在");
        }

        String newRole = userUpdateRequest.getUserRole();
        // 只在"真的要改角色"时校验：null 表示不动角色，不该因为没传角色而报错
        if (newRole != null) {
            if (!UserConstant.DEFAULT_ROLE.equals(newRole) && !UserConstant.ADMIN_ROLE.equals(newRole)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "角色只能是 user 或 admin");
            }
            // 不能把自己降级 —— 一个手滑管理面板就再也进不去，而且这是逻辑数据，
            // 得直接改库才能救回来。允许改自己的昵称头像，只拦"取消自己的管理员身份"
            if (userId == getLoginUser().getId() && !UserConstant.ADMIN_ROLE.equals(newRole)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能取消自己的管理员身份");
            }
        }

        User user = new User();
        user.setId(userId);
        user.setUserName(userUpdateRequest.getUserName());
        user.setUserAvatar(userUpdateRequest.getUserAvatar());
        user.setUserProfile(userUpdateRequest.getUserProfile());
        user.setUserRole(newRole);
        // updateById 默认走 NOT_NULL 策略：字段为 null 就不进 SET 子句，
        // 于是"传了才改、没传不动"的语义天然成立，不必手写动态 SQL。
        // （代价：没法把某个字段"改成 null"—— 这个项目里不需要清空昵称这类操作）
        boolean updateResult = this.updateById(user);
        if (!updateResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新用户失败");
        }

        log.info("管理员更新用户：id={}, 角色={}, 操作人id={}",
                userId, newRole, StpUtil.getLoginIdAsLong());
        return true;
    }

    @Override
    public boolean userDelete(String id) {
        checkAdminUser();

        long userId = ParamUtils.parseId(id);
        // 不能删自己：逻辑删除只是置标记，删完自己连管理面板都进不去
        if (userId == getLoginUser().getId()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能删除自己");
        }
        if (this.getById(userId) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在");
        }

        boolean removeResult;
        try {
            // removeById 因为实体上的 @TableLogic，会被翻译成 UPDATE `user` SET isDelete = 1 WHERE id = ?
            removeResult = this.removeById(userId);
        } catch (DuplicateKeyException e) {
            // 该账号历史上被删过一次：库里已有 (账号, isDelete=1) 这条记录，
            // 再置一条 (账号, 1) 就撞了复合唯一索引。详见 sql/03 的"已知局限"
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "该账号存在历史删除记录，无法重复删除");
        }
        if (!removeResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除用户失败");
        }

        log.info("管理员删除用户：id={}, 操作人id={}", userId, StpUtil.getLoginIdAsLong());
        return true;
    }

    @Override
    public UserVO getUserById(String id) {
        checkAdminUser();

        User user = this.getById(ParamUtils.parseId(id));
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在");
        }
        return toUserVO(user);
    }

    @Override
    public Page<UserVO> listUserByPage(UserQueryRequest userQueryRequest) {
        checkAdminUser();

        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        // 分页参数加固：逻辑已上提到 PageRequest（user / image 两处共用同一份），
        // 这里只负责取"能安全交给数据库"的值。
        // null / 0 / 负数 / 超过上限分别怎么处理，见 PageRequest#resolvePageNum / #resolvePageSize
        long pageNum = userQueryRequest.resolvePageNum();
        long pageSize = userQueryRequest.resolvePageSize();

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        // 条件全是"可选"的：没填就不进 WHERE（不为 null 也不为空串才算填了）
        if (!ParamUtils.isBlank(userQueryRequest.getId())) {
            wrapper.eq(User::getId, ParamUtils.parseId(userQueryRequest.getId()));
        }
        if (!ParamUtils.isBlank(userQueryRequest.getUserAccount())) {
            wrapper.like(User::getUserAccount, userQueryRequest.getUserAccount());
        }
        if (!ParamUtils.isBlank(userQueryRequest.getUserName())) {
            wrapper.like(User::getUserName, userQueryRequest.getUserName());
        }
        if (!ParamUtils.isBlank(userQueryRequest.getUserProfile())) {
            wrapper.like(User::getUserProfile, userQueryRequest.getUserProfile());
        }
        if (!ParamUtils.isBlank(userQueryRequest.getUserRole())) {
            // 角色用 eq 不用 like：like 会让 "adm" 也能查出 "admin"，而角色是给程序判断用的枚举语义
            wrapper.eq(User::getUserRole, userQueryRequest.getUserRole());
        }
        // 排序不是"好看"，是正确性问题：没有 ORDER BY 时 MySQL 不保证返回顺序，
        // 翻页会出现"某条记录重复出现、另一条永远看不到"
        // 第二排序键必须有：createTime 精确到秒，同一秒注册的几条记录之间顺序仍然不定，
        // 只按时间排还是可能翻页重复 / 漏项。补上 id，顺序才唯一确定。
        wrapper.orderByDesc(User::getCreateTime).orderByDesc(User::getId);

        Page<User> userPage = this.page(new Page<>(pageNum, pageSize), wrapper);

        // 实体分页 → VO 分页：total / current / size 原样搬，records 逐条脱敏
        Page<UserVO> userVoPage = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());

        List<UserVO> voList = userPage.getRecords().stream()
                .map(user -> toUserVO(user))
                .toList();
        userVoPage.setRecords(voList);
        return userVoPage;
    }

    /**
     * 实体 → 脱敏 VO（管理员场景）。
     *
     * <p>和 {@link #toLoginUserVO(User)} 字段一样但各写一份，理由见 {@code UserVO} 的类注释：
     * 两个 VO 是两个独立的脱敏边界，让其中一边加字段时不必牵动另一边。
     */
    @Override
    public UserVO toUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        userVO.setId(String.valueOf(user.getId()));
        userVO.setUserAccount(user.getUserAccount());
        userVO.setUserName(user.getUserName());
        userVO.setUserAvatar(user.getUserAvatar());
        userVO.setUserProfile(user.getUserProfile());
        userVO.setUserRole(user.getUserRole());
        userVO.setCreateTime(user.getCreateTime());
        return userVO;
    }

}
