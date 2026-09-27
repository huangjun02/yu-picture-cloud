package com.huang.yupicture.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.constant.UserConstant;
import com.huang.yupicture.mapper.UserMapper;
import com.huang.yupicture.model.dto.user.UserRegisterRequest;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.LoginUserVO;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.PasswordUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        // TODO(用户模块收官): 这段"按账号查一条"在 userLogin / userRegister 里重复，届时抽成
        //  private User getByAccount(String account)，两处调用都换成它
        User user = this.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserAccount, userAccount));

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

        // 2. 查重 —— 只是"友好提示"，真正的唯一性由数据库 uk_userAccount 兜底：
        //    并发下两个请求会双双通过这一关，靠唯一索引挡住后一个
        // TODO(用户模块收官): 与 userLogin 里"按账号查一条"一起抽成 private User getByAccount(String)。
        //  另：userLogin 用链式、本方法拆成三步，两种等价；抽方法时顺手统一风格。
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserAccount, userAccount);
        User user = this.getOne(queryWrapper);
        if (user != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }

        // 3. 加密 + 落库（明文到此为止，以下全是密文）
        user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(PasswordUtils.encrypt(userPassword));
        // 默认昵称取账号，之后允许用户自己改（可空字段）
        user.setUserName(userAccount);
        // 角色显式赋值：DDL 虽然写了 DEFAULT 'user'，但那条默认值生效的前提是
        // MyBatis-Plus 的 NOT_NULL 插入策略（null 字段不进 SQL）—— 依赖隐式行为不如写明意图
        user.setUserRole(UserConstant.DEFAULT_ROLE);
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败");
        }
        // TODO(用户模块收官): 并发同名注册会双双通过上面的查重，第二个在此撞唯一索引抛
        //  DuplicateKeyException，被全局处理器翻成 50000。届时 catch 它并翻译成"账号已存在"。

        // 4. 返回 id 的字符串形式；注册后不自动登录（与 Controller 的约定一致）
        //    ⚠️ 日志只记 id 和账号，绝不记密码 —— 打印整个请求体会把明文密码写进日志文件
        log.info("用户注册成功：id={}, account={}", user.getId(), user.getUserAccount());
        return String.valueOf(user.getId());
    }
}
