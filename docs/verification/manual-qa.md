# 本地运行与人工验收

更新时间：2026-10-24

## 已验证环境

- Java 17 运行 Spring Boot 2.7.12 项目；源码按 Java 11 语言级别编译。
- MySQL 8.0.39 隔离实例：`127.0.0.1:3307`，数据目录 `F:\intern2026\.local-runtime\mysql-test`。点评 schema 有 11 张表及 14 个样例商户；电商各服务使用独立 schema。
- Redis 3.2.100：`127.0.0.1:6379`；Nacos 2.2.3 standalone：`127.0.0.1:8848`；RabbitMQ 3.13.7：`127.0.0.1:5672`；Seata 1.5.2 TC：`127.0.0.1:8091`（控制台 `http://127.0.0.1:7091/`）。
- Spring 服务：Gateway `8080`、商品 `8081`、用户 `8082`、购物车 `8083`、订单 `8086`、支付 `8087`、本地生活 `8088`。
- 静态前端 `http://127.0.0.1:8090/`。七个 Spring 服务的日志写入仓库内忽略提交的 `.runtime` 目录；依赖运行数据位于仓库外 `F:\intern2026\.local-runtime`。
- `deploy/nacos` 中四份共享配置由 `python scripts/publish_nacos_config.py` 发布。JWT JKS 在 `dev-secrets`，被 `.gitignore` 排除。

## 启动与停止

在仓库根目录开 PowerShell：

```powershell
.\scripts\start-local-dependencies.ps1
python scripts/publish_nacos_config.py
$env:DB_PORT = '3307'
$env:JWT_KEYSTORE_LOCATION = 'file:./dev-secrets/hmall.jks'
$env:JWT_KEYSTORE_PASSWORD = '<本机 JKS 密码>'
.\scripts\start-services.ps1
node frontend/server.js
```

`start-local-dependencies.ps1` 会从本机官方 Seata 1.5.2 压缩包解压并启动 TC，创建业务库的 AT `undo_log`，并准备支付发件箱表和券核销字段。`start-services.ps1` 会启动七个 Spring 可执行 JAR，并将本地生活服务端口覆写到 8088；Java 17 需要的 Seata 兼容启动参数已包含在脚本中。若 JAR 不存在，先执行 `.\mvnw.cmd -DskipTests install`。前端需另开终端运行；若 8090 已有监听则无需重复启动。停止由项目脚本启动的 Java 业务服务：

```powershell
.\scripts\stop-services.ps1
```

依赖服务可在任务结束时通过对应进程/服务管理器停止；不要停止或改写机器原有 MySQL80（3306）。所有项目服务只绑定本机地址，测试脚本会拒绝远程 Gateway。

## IDEA 快速核验

1. IDEA 选择 **Open**，打开仓库根目录的 `pom.xml`，以 Maven 项目导入；Project SDK 选 JDK 17，Maven Wrapper 选项目自带 wrapper。
2. 等 Maven 识别 9 个业务/公共模块后，展开 `local-life-service`，确认 `UserController.info` 与 `BlogCommentsController` 路由能被索引；运行根目录 Maven 的 `test` 生命周期。
3. 服务已启动时不要在 IDEA 再起第二份同端口服务。打开 <http://127.0.0.1:8090/login2.html>，在“用户名”栏输入 `Jack`、密码栏输入 `123` 并勾选用户协议后登录；到个人页的“编辑资料”修改昵称或介绍并保存，再刷新确认新资料仍在。演示结束后可恢复原值。
4. 打开 <http://127.0.0.1:8090/vouchers.html>，确认未登录也能浏览活动；登录后抢券并在“我的券”中余额支付。待支付券可取消；支付后页面显示核销码并提供退款；Jack 演示管理员可在页面底部核销该码。商户详情页还可购买普通券。若没有有效活动，设置 `DEMO_LOGIN_PASSWORD` 后运行 `python scripts/seed_demo_voucher.py` 创建本机演示券。
5. 打开 <http://127.0.0.1:8090/commerce.html>，搜索商品、加购、创建订单并余额支付；在订单完成后检查支付结果和状态。
6. 打开笔记详情页，查看评论列表并新增一条评论；再在 Network 面板确认请求通过 `/api` 到 Gateway。评论列表只显示未隐藏评论。
7. 业务服务日志位于 `.runtime`；Nacos 等依赖日志在各自的运行目录。若 IDEA 启动某个 Spring 服务失败，先检查对应日志、端口占用、JDK 及 `JWT_KEYSTORE_LOCATION` / `JWT_KEYSTORE_PASSWORD` 环境变量。若在 IDEA 中手动创建演示券，还需设置 `LOCAL_LIFE_ADMIN_USER_ID=1`。

## Seata 与压测复现

先确认 Seata 控制台 <http://127.0.0.1:7091/> 可访问，TC 的 8091 端口正在监听。默认演示控制台账号由本机官方发行包提供；不要把这套配置暴露到公网。`shared-seata.yaml` 经 `publish_nacos_config.py` 发布后，交易、商品、购物车、用户和支付服务会连接 TC。

在已启动的隔离环境中，从仓库根目录依次运行：

```powershell
python scripts/test_seata_rollback.py
python scripts/test_seckill_contention.py
python scripts/benchmark_local.py --concurrency 10 --requests 3000 --warmup 100
```

回滚脚本创建库存 1 的临时商品，先加购再尝试下单 2 件，检查交易订单与明细、购物车和库存，并清理临时商品。秒杀脚本创建临时券，结束后下架，已抢到的券订单仍留在隔离库。读压测不写业务数据，自动把逐请求 CSV 与汇总 JSON 写到 `docs/verification/results`。三份脚本都拒绝非本机 Gateway；数据库核验使用本机 MySQL CLI，可通过 `MYSQL_CLIENT`、`DB_PORT`、`DB_USERNAME` 和 `DB_PASSWORD` 设置连接。压测时不要并行运行其他业务脚本，否则结果会混入额外负载。

## 自动化验证记录

| 日期 | 命令/动作 | 结果 |
|---|---|---|
| 2026-10-24 | `python scripts/check_api_surface.py` | 72 个 HTTP mappings |
| 2026-10-24 | `python scripts/test_http_integration.py` | 87 次真实 Gateway HTTP 调用通过；包含支付、重试/对账、取消、核销、退款、重复操作拒绝和余额回退断言 |
| 2026-10-24 | `python scripts/test_frontend_smoke.py` | 13 页面 HTTP 200，无页面级 JavaScript 错误（需要设置隔离安装目录的 `PYTHONPATH`） |
| 2026-10-24 | Edge 页面交互检查 | 活动页只展示有效演示券；门店 1 和 21 的探店笔记从真实接口返回；4 个页面无 `pageerror` |
| 2026-10-24 | `python scripts/test_seata_rollback.py` | 库存不足返回 400；交易订单及明细计数不变、购物车恢复、库存仍为 1；TC 日志有全局回滚 |
| 2026-10-24 | `python scripts/test_seckill_contention.py` | 同一账号对库存 1 的券同时发起 20 次请求：1 次成功、库存 0、订单 1 条 |
| 2026-10-24 | `python scripts/benchmark_local.py --concurrency 10 --requests 3000 --warmup 100` | 活动列表读接口 3000/3000 成功；414.87 请求/秒，P95 38.25 ms，P99 50.98 ms；原始结果在 `docs/verification/results` |
| 2026-10-23 | `python scripts/test_commerce_browser.py` | Edge 完成商品搜索、购物车、下单、余额支付和订单状态检查 |
| 2026-10-23 | `.\mvnw.cmd -q -DskipTests install` | 通过；10 个 Maven reactor 项目 |

浏览器测试依赖安装到仓库外隔离目录：`python -m pip install --target F:\intern2026\.local-runtime\python-packages -r scripts/requirements-test.txt`，再设置 `$env:PYTHONPATH` 指向此目录。脚本会自动寻找 Edge；如未自动找到，可设置 `EDGE_EXECUTABLE`。

## 测试账号及数据边界

集成测试用来源 SQL 中的 Jack 演示账号（密码 `123`），测试写入隔离数据库，不要把测试地址改为远程环境。短信验证码登录新建的账号会生成不可用于登录的随机密码且初始余额为 0，因此不能直接做余额支付；请使用有余额的密码账号登录并重新加购下单。支付密码错误或余额不足现在应返回 400 业务消息，不应显示 500。验证码由本地 Redis 保存，未接短信供应商。图片上传目录默认 `frontend/public/imgs`，自动化上传用例会删除测试图片。测试可能留下业务记录，应在可丢弃的开发库执行。

## 支付消息及券履约验证

`pay_outbox` 在余额扣款和支付单成功状态的 Seata 全局事务中落库。发布器启用 RabbitMQ publisher confirms 和 mandatory returns；失败按 2、4、8、16、32、60 秒上限退避并记录错误。每分钟扫描已支付、已确认但较早的通知，与业务订单状态核对；缺失事件会补建，业务单仍待支付时会重新发布。消费者对已经更新的订单允许重复通知。

验证记录：将测试 outbox 事件临时改到不存在的 routing key，RabbitMQ 返回 `NO_ROUTE`，发件箱状态恢复待重试且 attempts 增加；修复 routing key 后事件重新确认。随后将一条已支付商城订单状态临时改回待支付，定时对账日志发现差异并补发，订单最终恢复已支付。HTTP 联调还覆盖核销码只在待使用状态返回、重复核销 400、已使用券退款 400、未核销券退款成功且余额增加 100 分、重复退款 400，以及取消支付单后禁止再次支付。

## 未验证范围

本机读接口压测是 Windows 单机、一个客户端进程、单个服务实例的小规模结果，不能外推为生产容量。20 次秒杀请求都来自同一账号，只验证锁、唯一约束和库存 1 场景，不代表多用户高并发超卖已穷尽验证。Seata AT 已覆盖余额扣款、支付状态、发件箱事件及退款涉及的用户、支付和券订单写入；RabbitMQ 通知仍是异步最终一致，不属于一个同步数据库事务。评论隐藏/管理及异常参数的完整边界矩阵尚未实现或测试。
