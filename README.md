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

在 IDEA 的 Database 工具 / Navicat 中执行：

```
sql/01_create_database.sql
```

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
├── common/                        通用返回结构
│   ├── ErrorCode.java             错误码枚举
│   ├── BaseResponse.java          统一响应体 {code, data, message}
│   └── ResultUtils.java           响应构造工具
├── controller/                    接口层
│   └── HealthController.java
└── YuPictureCloudApplication.java 启动类
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
- [ ] **用户模块** —— 注册、登录、权限校验
- [ ] **图片模块** —— 上传、检索、审核
- [ ] **空间模块** —— 私有空间 / 团队空间、成员管理

## 说明

- 敏感配置一律走 `application-local.yml` + profile 覆盖机制，仓库中不含任何真实凭据。
- 单元测试位于 `src/test/java`，运行 `mvn test`。
