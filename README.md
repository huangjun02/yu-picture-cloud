# 智能协同云图库 · 后端

> 个人学习项目：从零实现一个支持多人协作的云图库（图片托管 + 检索 + 空间管理）。
> 后端核心逻辑手写，前端由 AI 辅助生成，全程记录踩坑与设计权衡。

## 技术栈

| 层次 | 选型 |
|---|---|
| 运行环境 | JDK 21 |
| 框架 | Spring Boot 3.5.16 |
| 持久层 | MyBatis-Plus 3.5.17 + MySQL 8 |
| 缓存 | Redis（Lettuce 连接池 + commons-pool2） |
| 权限 | Sa-Token 1.46.0（Token 存 Redis） |
| 接口文档 | Knife4j 4.5.0（OpenAPI 3） |
| 构建 | Maven 3.9 |

## 快速开始

### 1. 环境要求

JDK 21 · Maven 3.9+ · MySQL 8 · Redis

### 2. 初始化数据库

在 IDEA 的 Database 工具 / Navicat 中执行（按编号顺序）：

| 脚本 | 作用 | 什么时候跑 |
|---|---|---|
| `sql/01_create_database.sql` | 建库 `yu_picture_cloud`（utf8mb4） | 首次 |
| `sql/02_create_table_user.sql` | 建 `user` 表（含索引） | 首次 |
| `sql/03_init_test_user.sql` | 插入联调种子账号 `huangjun` | 可选 |
| `sql/04_alter_user_unique_index.sql` | 账号唯一索引改为 `(userAccount, isDelete)` | **只有"表已存在"的老库需要** |

> 为什么 `02` 里已经有索引定义，还要单独一个 `04`？
> `02` 用的是 `CREATE TABLE IF NOT EXISTS` —— 表一旦存在，**重跑它什么都不会发生**。
> 所以给老库改索引只能靠独立的 `ALTER` 语句，即 `04`。
> 这是数据库脚本的通用规矩：**执行过的脚本不再改语义，新的变更另起一个编号的文件**。

### 3. 配置本地密码

`application.yml` 里的密码是占位符 `${MYSQL_PASSWORD:}`，真实密码放在 `application-local.yml`（**已被 .gitignore 忽略，不会进版本库**）：

```yaml
spring:
  datasource:
    password: 你的 MySQL 密码
```

### 4. 启动

```bash
mvn spring-boot:run
```

### 5. 验证

| 用途 | 地址 |
|---|---|
| 健康检查 | http://localhost:8123/api/health |
| 接口文档 | http://localhost:8123/api/doc.html |

健康检查预期返回：

```json
{"code":0,"data":"ok","message":"ok"}
```

## 项目结构

```
src/main/java/com/huang/yupicture/
├── common/                          通用层
│   ├── BaseResponse.java            统一响应体 {code, data, message}
│   ├── ResultUtils.java             响应构造工具
│   ├── ErrorCode.java               错误码枚举
│   ├── BusinessException.java       业务异常（message 直接给用户看）
│   └── GlobalExceptionHandler.java  全局异常 → 统一响应（含 NotLoginException → 40100）
├── config/                          配置
│   ├── MybatisPlusConfig.java       @MapperScan + 分页插件
│   ├── CorsConfig.java              全局跨域（Filter 层，早于 DispatcherServlet）
│   └── SaTokenConfig.java           全局登录拦截 + 免登录白名单
├── constant/UserConstant.java       角色、盐值等常量
├── controller/                      接口层：只做「收参 → 调 Service → 包返回」
│   ├── HealthController.java
│   └── UserController.java
├── mapper/UserMapper.java           持久层（XML 在 resources/mapper/）
├── model/
│   ├── entity/User.java             实体，与表一一对应
│   ├── dto/user/                    入参：Login / Register / Add / Update / Query
│   ├── dto/common/PageRequest.java  分页基类（图片模块也会用）
│   └── vo/                          出参（脱敏）：LoginUserVO / UserVO
├── service/
│   ├── UserService.java
│   └── impl/UserServiceImpl.java    业务规则都在这一层（含权限判定）
├── utils/PasswordUtils.java         加盐摘要
└── YuPictureCloudApplication.java   启动类
```

## 接口约定

所有接口统一返回三层结构，前端 axios 拦截器按此协议拆包：

```json
{ "code": 0, "data": "...", "message": "ok" }
```

- `code = 0` 表示成功，其余为错误（错误码见 `common/ErrorCode.java`）
- 全局路由前缀 `/api`（由 `server.servlet.context-path` 配置）

## 开发进度

- [x] **项目初始化** —— Spring Boot 骨架、统一返回结构、健康检查（TDD）、数据库、MyBatis-Plus / Sa-Token / Knife4j 接入
- [x] **用户模块** —— 注册、登录、登出、获取当前登录用户；全局登录拦截 + 管理员权限校验；管理员用户管理（新增 / 更新 / 删除 / 分页检索）
- [ ] **图片模块** —— 上传、检索、审核
- [ ] **空间模块** —— 私有空间 / 团队空间、成员管理

## 说明

- 敏感配置一律走 `application-local.yml` + profile 覆盖机制，仓库中不含任何真实凭据。
- 单元测试位于 `src/test/java`，运行 `mvn test`。
