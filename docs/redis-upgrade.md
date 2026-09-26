# Redis 升级记录（2026-09-27）

> 起因、现状、怎么起停、出问题怎么回退。排障时的第一条线索就在这里。

## 一句话现状

本机 Redis 已从 **3.0.504** 升级为 **8.10.2**，以 Windows 服务 `Redis` 跑在**标准端口 6379**、开机自启。

| 项 | 值 |
|---|---|
| 版本 | 8.10.2 |
| 端口 | 6379（只绑 `127.0.0.1`，无密码） |
| 形态 | Windows 服务 `Redis`，`START_TYPE = AUTO_START`，账户 `LocalSystem` |
| 程序目录 | `D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service\` |
| 数据目录 | `D:\Redis\Redis-8.10.2\data` |
| 来源 | [redis-windows](https://github.com/redis-windows/redis-windows) 的 `Redis-8.10.2-Windows-x64-cygwin-with-Service.zip`（基于官方源码编译，**非官方发行版，仅本地开发用**） |

## 为什么要升级

原先只有 **Redis 3.0.504**（Microsoft 移植版末版，2016 停更）。项目（Spring Boot 3.5.16 + Sa-Token 1.46）启用 Sa-Token 的 Redis 会话存储后，**登录接口直接返回 50000**：

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

spring-data-redis 3.5 + Lettuce 6.x 在「带过期的 SET」上下发的正是 `SET key value EXAT <绝对毫秒>`。

**注意：换客户端没用**（Lettuce → Jedis 也一样）—— 命令参数由 spring-data-redis 组装，不是客户端决定的。

## 升级过程（做了什么）

1. 下载 `Redis-8.10.2-Windows-x64-cygwin-with-Service.zip`（约 20 MB），解压到 `D:\Redis\Redis-8.10.2\`
   - ⚠️ cygwin 版 `redis-server.exe` **只认 cygwin 路径**（`/cygdrive/d/...`），传 `D:\...` 会被当相对路径、找不到配置 → 一律用包内自带的 `RedisService.exe` 启动（它自动转路径）
2. 没有管理员权限时，先在 **6380** 端口跑通临时实例（旧服务当时占着 6379），把整条链路验证完
3. 拿到管理员窗口后完成彻底替换：

```powershell
net stop Redis                      # 停旧服务
sc.exe delete Redis                 # 删旧服务注册（旧程序文件留在 C:\Program Files\Redis，不删）
Get-Process -Name RedisService,redis-server | Stop-Process -Force   # 停 6380 临时实例
cd D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service
.\RedisService.exe install -c "D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service\redis.conf" --dir "D:\Redis\Redis-8.10.2\data" --port 6379 --service-name Redis --start-mode auto
net start Redis
```

## 日常起停 / 验收

```powershell
net start Redis            # 启动（也可以 services.msc 或任务管理器里点）
net stop Redis             # 停止
sc.exe query Redis         # 看状态
```

```powershell
cd D:\Redis\Redis-8.10.2\Redis-8.10.2-Windows-x64-cygwin-with-Service
.\redis-cli.exe -p 6379 INFO server | Select-String "redis_version"   # redis_version:8.10.2
.\redis-cli.exe -p 6379 SET k v EXAT 4102444800                       # OK（3.0 会报 syntax error）
.\redis-cli.exe -p 6379 --scan                                        # 列 key（别用 KEYS '*'，见踩坑 4）
```

**怎么确认「会话真的存在 Redis 里」**（而不是悄悄退回内存存储）：

1. 登录后 `redis-cli --scan` 应能看到 `satoken:login:token:*` 和 `satoken:login:session:*`
2. **重启后端**，拿重启前的 cookie 请求 `/api/user/get/login` → 仍返回 `code 0`；内存存储时这一步必然掉登录

## 出问题怎么回退

旧版程序文件仍在 `C:\Program Files\Redis\`（未删除）：

```powershell
net stop Redis                       # 停新服务
sc.exe delete Redis                  # 删注册
sc.exe create Redis binPath= "\"C:\Program Files\Redis\redis-server.exe\" --service-run \"C:\Program Files\Redis\redis.windows-service.conf\"" start= auto
net start Redis                      # 装回旧服务（需管理员）
```

同时把 `pom.xml` 里 `sa-token-redis-jackson` 那段注释掉（退回 Sa-Token 内存存储），否则 3.0 上写会话会再次 50000。

## 踩过的坑

1. **`net session` 判断不了管理员权限** —— 本机它返回成功，但 `sc delete` / `net stop` / `RedisService install` 全被拒。可靠判据只有：
   ```powershell
   ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
   ```
2. **`sc delete` 之后服务不会立刻消失**：紧接着 `RedisService install` 可能报「服务已存在」，等 5 秒重跑 install 即可。
3. **两个实例不能共用同一个 `data` 目录**：会互相抢 `dump.rdb` / `redis_6379.pid`。彻底替换前先把临时实例停掉。
4. **MSYS 会先展开 `*` 再传给原生 redis-cli** → `redis-cli KEYS '*'` 报 `wrong number of arguments for 'keys'`；改用 `--scan`。
5. **非管理员时的合法解法是换端口**（当时用 6380），而不是去抢 6379 —— 别人占着的服务你既停不掉也抢不到。

## 相关文件

- `pom.xml` — `sa-token-redis-jackson` 依赖（启用中，注释里有完整背景）
- `src/main/resources/application.yml` — `spring.data.redis.port: 6379`
