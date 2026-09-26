# Redis 升级记录（2026-09-27）

> 起因、现状、怎么起停、将来怎么做到「干净替换」。排障时的第一条线索就在这里。

## 为什么要升级

本机原先只有 **Redis 3.0.504**（Microsoft 移植版的最后一版，2016 年停更，作为 Windows 服务 `Redis` 常驻 6379）。

项目（Spring Boot 3.5.16 + Sa-Token 1.46）启用 Sa-Token 的 Redis 会话存储后，**登录接口直接返回 50000**。堆栈：

```
RedisSystemException: Error in execution
  └─ RedisCommandExecutionException: ERR syntax error
```

根因不是配置写错，而是**版本语法差**：

| 命令 | 需要版本 | Redis 3.0.504 |
|---|---|---|
| `SET k v EX <秒>` | 1.0+ | 支持 |
| `SET k v PX <毫秒>` | 2.6+ | 支持 |
| `SET k v KEEPTTL` | **6.0+** | `ERR syntax error` |
| `SET k v EXAT <绝对秒>` | **6.2+** | `ERR syntax error` |

spring-data-redis 3.5 + Lettuce 6.x 在「带过期的 SET」上下发的正是 `SET key value EXAT <绝对毫秒>` → 3.0 直接报语法错 → 写会话失败。

**注意：换客户端没用**（Lettuce → Jedis 也一样）—— 命令参数由 spring-data-redis 组装，不是客户端决定的。

## 现在是什么状态

| 端口 | 版本 | 形态 | 用途 |
|---|---|---|---|
| **6380** | **8.10.2** | 用户进程（`RedisService.exe`） | **本项目连这个**（`spring.data.redis.port: 6380`） |
| 6379 | 3.0.504 | Windows 服务 `Redis`（自启） | 遗留，闲置（腾端口要管理员权限） |

- 程序目录：`D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service\`
- 数据目录：`D:\Redis\Redis-8.10.2\data`
- 来源：[redis-windows](https://github.com/redis-windows/redis-windows) 的 `Redis-8.10.2-Windows-x64-cygwin-with-Service.zip`（基于官方源码编译，非官方发行；**仅本地开发用**）
- 只绑 `127.0.0.1`，无密码（和旧的 6379 一致）

## 日常起停

```bash
# 启动（脱离进程，关掉终端也不受影响）
"D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service\RedisService.exe" run --port 6380 --dir D:\Redis\Redis-8.10.2\data

# 停止（优雅关闭，会落盘）
"D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service\redis-cli.exe" -p 6380 SHUTDOWN

# 验收（版本 + 关键语法）
redis-cli.exe -p 6380 INFO server        # → redis_version:8.10.2
redis-cli.exe -p 6380 SET k v EXAT 4102444800   # → OK（3.0 会报 ERR syntax error）
```

开机自启：启动文件夹 `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup` 里放一个 `start-redis-6380.vbs`（静默、无黑窗）。命令行程序不弹黑窗必须走 VBS 包一层 `WScript.Shell.Run`。

## 将来怎么「干净替换」（需要管理员）

目标：让 **6379 直接就是 Redis 8.10.2**，去掉双实例并存。全程约 1 分钟，**需要管理员权限**（本会话没有）：

```powershell
# 1) 停并删除旧服务（旧程序文件在 C:\Program Files\Redis，不删，可回退）
net stop Redis
sc.exe delete Redis

# 2) 用新版自带的服务包装器装服务（默认服务名就是 Redis）
cd D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service
.\RedisService.exe install -c "D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service\redis.conf" --dir "D:\Redis\Redis-8.10.2\data" --port 6379 --service-name Redis --start-mode auto
net start Redis
```

然后把 `application.yml` 的 `spring.data.redis.port` 改回 `6379`，跑 `mvn -o test` 确认 14 个测试全绿。

**回退**：`sc.exe create Redis binPath= "\"C:\Program Files\Redis\redis-server.exe\" --service-run \"C:\Program Files\Redis\redis.windows-service.conf\"" start= auto` + `net start Redis`。

## 踩过的坑（下次别重复）

1. **`net session` 判断不了管理员权限** —— 本会话它返回成功，`sc delete` 却「拒绝访问」。可靠判断只有：
   ```powershell
   ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
   ```
2. **cygwin 版 redis-server 只认 cygwin 路径**（`/cygdrive/d/...`），传 `D:\...` 会被当成相对路径 → 找不到配置。**用 `RedisService.exe` 代为启动**，它自动转换 Windows 路径。
3. **服务类操作全部要管理员**：`sc delete` / `net stop` / `RedisService install` 三个都是。
4. **不能动别人占着的端口**：6379 被旧服务占，非管理员既停不掉也抢不到 → 换端口（6380）是最省事的合法解法。

## 相关文件

- `pom.xml` — `sa-token-redis-jackson` 依赖（2026-09-27 启用，注释里有完整背景）
- `src/main/resources/application.yml` — `spring.data.redis.port: 6380`
