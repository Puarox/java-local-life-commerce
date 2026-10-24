# Java Local Life Commerce

一个 Maven 多模块 Java 项目，把本地生活内容与优惠券业务、电商交易能力合并到统一身份和服务体系中。当前项目以 Java 11 编译，Spring Boot 2.7.12、Spring Cloud 2021.0.3、Spring Cloud Alibaba 2021.0.4.0。

## 已迁入的能力

- 本地生活：商户缓存与逻辑过期、Redis Geo 附近商户、探店笔记、点赞与关注 Feed、签到、普通券购买与限时券秒杀。
- 电商交易：商品、购物车、统一订单、余额扣款、支付状态和消息通知相关模块。
- 融合部分：Gateway 统一路由；用户服务统一密码登录、开发验证码登录与 JWT；`UserContext` 在服务间传递用户 ID；点评用户资料通过 Feign 查询统一用户服务；券订单通过共享支付服务完成余额支付，并提供 MySQL outbox、RabbitMQ 重试/对账和取消、核销、退款履约闭环。
- 秒杀一致性：Redisson 用户维度互斥锁、数据库条件扣库存、订单唯一索引共同约束一人一券。

本仓库是源码整合项目，不代表已完成生产部署。验证码目前由 Redis 保存，需通过本地 Redis 读取以完成开发登录；未接入真实短信供应商。

## 模块

| 模块 | 职责 |
|---|---|
| `hm-gateway` | 统一入口、服务路由、JWT 校验和用户上下文传递 |
| `user-service` | 用户登录、账户资料、余额扣减 |
| `item-service` | 电商商品及库存 |
| `cart-service` | 购物车 |
| `trade-service` | 电商订单与订单明细 |
| `pay-service` | 支付订单与支付结果通知 |
| `local-life-service` | 商户、笔记、关注、签到、优惠券与秒杀订单 |
| `hm-api` / `hm-common` | Feign 契约、JWT、用户上下文与公共错误处理 |

## 构建

需要 JDK 11 或更高版本。仓库包含 Maven Wrapper：

```powershell
.\mvnw.cmd -v
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd test
.\mvnw.cmd package
python scripts/check_api_surface.py
python -m pip install -r scripts/requirements-test.txt
python scripts/test_frontend_smoke.py
```

前端冒烟脚本需要本机已安装的 Edge/Chromium；若 Edge 不在默认安装位置，可设置 `EDGE_EXECUTABLE`。启动前端方式见下方“本地前端”。

依赖服务未启动时，单元测试仍可运行；接口联调需要 Nacos、MySQL、Redis 和 RabbitMQ，并配置对应服务的数据库及共享 Nacos 配置。每个服务使用独立 schema，原始配置会连接 Nacos，不提供可直接用于生产的默认凭据。

本地生活原始库表脚本在 [`local-life-service/src/main/resources/db/hmdp.sql`](local-life-service/src/main/resources/db/hmdp.sql)。脚本含 `DROP TABLE` 和旧版演示数据，执行前应在新建的空数据库中导入，不要对现有数据库运行。秒杀订单表必须包含 `(user_id, voucher_id)` 唯一索引。依赖启动脚本会幂等初始化 `hm-pay.pay_outbox`、券订单核销字段及 Seata `undo_log`。

## 配置

- `NACOS_SERVER_ADDR`：Nacos 地址，默认 `127.0.0.1:8848`。
- `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`：点评服务数据库连接。
- `REDIS_HOST`、`REDIS_PORT`：Redis 连接。
- `JWT_KEYSTORE_LOCATION`、`JWT_KEYSTORE_PASSWORD`：JWT 私钥库位置与密码。密钥文件被 Git 忽略；可用 JDK `keytool` 在 `dev-secrets` 生成本地密钥库，并将环境变量指向它。
- `LOCAL_LIFE_ADMIN_USER_ID`：允许创建普通券和限时券的演示管理员用户 ID；默认 `0`（禁用创建）。本地启动脚本默认设为来源 SQL 中的 Jack 账号 ID `1`。

首次本地运行可在仓库根目录生成开发密钥（把占位密码替换成自己的本地值）：

```powershell
New-Item -ItemType Directory -Force dev-secrets
keytool -genkeypair -alias hmall -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Java Local Life Commerce" -keystore dev-secrets/hmall.jks -storetype JKS -storepass YOUR_LOCAL_PASSWORD -keypass YOUR_LOCAL_PASSWORD
$env:JWT_KEYSTORE_LOCATION = "file:./dev-secrets/hmall.jks"
$env:JWT_KEYSTORE_PASSWORD = "YOUR_LOCAL_PASSWORD"
```

本地生活 API 单独启动时默认端口为 `8081`；统一启动脚本会覆盖为 `8088`，以避开商品服务的 `8081`。Gateway 为 `8080`，静态前端为 `8090`。其余服务和依赖端口见 [`docs/verification/manual-qa.md`](docs/verification/manual-qa.md)。

本机隔离环境完成配置后，可执行 `scripts/start-services.ps1` 启动七个 Spring 服务，`scripts/stop-services.ps1` 停止由脚本启动的 Java 服务。启动前要启动 MySQL、Redis、Nacos、RabbitMQ 和 Seata 1.5.2 TC，并发布 `deploy/nacos` 中的共享配置。`scripts/start-local-dependencies.ps1` 会调用 `scripts/start-seata.ps1`，为交易、商品、购物车、用户、支付和本地生活六个库初始化 AT 模式 `undo_log`；Seata 1.5.2 官方压缩包放在 `F:\intern2026\.local-runtime\seata-server-1.5.2.zip`。本仓库不提交第三方运行时二进制或数据库文件。接口联调脚本 `python scripts/test_http_integration.py` 会写入隔离测试数据库，只允许访问本机 Gateway；测试账号和数据准备见人工验收文档。

首次需要在前端演示秒杀时，可在服务启动后设置 `DEMO_LOGIN_PASSWORD` 为本机 Jack 演示账号密码，再运行 `python scripts/seed_demo_voucher.py`。脚本只连接本机 Gateway，已有有效演示活动时不会重复创建。打开 <http://127.0.0.1:8090/vouchers.html> 浏览活动、抢券和查看“我的券”；普通券可从商户详情购买。两种券都在“我的券”完成余额支付，待支付单可取消，支付后的券显示核销码并支持退款。演示管理员（`LOCAL_LIFE_ADMIN_USER_ID`，本地默认为 Jack ID 1）登录后可在“我的券”底部使用商户核销表单。

## 架构与评测记录

- [`docs/provenance/source-inventory.md`](docs/provenance/source-inventory.md)：来源仓库、迁移边界和可复核资产清单。
- [`docs/architecture.md`](docs/architecture.md)：统一身份和业务模块边界。
- [`docs/evaluation.md`](docs/evaluation.md)：当前验证范围、运行依赖与后续压测记录表。
- [`docs/verification/api-test-matrix.md`](docs/verification/api-test-matrix.md)：逐接口验证矩阵及实际执行记录。
- [`docs/verification/manual-qa.md`](docs/verification/manual-qa.md)：前端启动、人工操作流程和本机环境记录。

## 本地前端

点评静态页面在 [`frontend/public`](frontend/public)，从项目根目录运行 `node frontend/server.js` 后打开 <http://127.0.0.1:8090/>。静态服务器会把 `/api/*` 转发给 Gateway 的 8080 端口；Gateway 与依赖服务仍需先运行。

压测数字只在完成可复现压测后填写，源码行数、模块数和编译结果不会写成吞吐指标。
