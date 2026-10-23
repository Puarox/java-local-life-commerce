# API 验证矩阵

更新时间：2026-10-23。接口基于源码中的 Controller mapping 盘点。`待联调` 表示目前没有真实 HTTP 调用证据；编译成功不算接口通过。

## 电商服务

| 服务 | 接口 | 当前状态 |
|---|---|---|
| 用户 | `POST /users/login`、`POST /users/code`、`GET /users/profile/{id}`、`PUT /users/money/deduct` | 待联调 |
| 地址 | `GET /addresses`、`GET /addresses/{addressId}` | 待联调 |
| 商品 | `GET /items/page`、`GET /items?ids=`、`GET /items/{id}`、`POST /items`、`PUT /items/status/{id}/{status}`、`PUT /items`、`DELETE /items/{id}`、`PUT /items/stock/deduct` | 待联调 |
| 搜索 | `GET /search/list` | 待联调 |
| 购物车 | `POST /carts`、`PUT /carts`、`DELETE /carts/{id}`、`GET /carts`、`DELETE /carts?ids=` | 待联调 |
| 订单 | `GET /orders/{id}`、`POST /orders`、`PUT /orders/{orderId}` | 待联调 |
| 支付 | `GET /pay-orders`、`POST /pay-orders`、`POST /pay-orders/{id}` | 待联调 |

## 本地生活服务

| 服务 | 接口 | 当前状态 |
|---|---|---|
| 商户 | `GET /shop/{id}`、`POST /shop`、`PUT /shop`、`GET /shop/of/type`、`GET /shop/of/name` | 待联调 |
| 商户分类 | `GET /shop-type/list` | 待联调 |
| 笔记 | `POST /blog`、`PUT /blog/like/{id}`、`GET /blog/of/me`、`GET /blog/hot`、`GET /blog/{id}`、`GET /blog/likes/{id}`、`GET /blog/of/user`、`GET /blog/of/follow` | 待联调 |
| 关注 | `PUT /follow/{id}/{isFollow}`、`GET /follow/or/not/{id}`、`GET /follow/common/{id}` | 待联调 |
| 用户扩展 | `GET /user/me`、`GET /user/{id}`、`GET /user/info/{id}`、`POST /user/sign`、`GET /user/sign/count`、`POST /user/logout` | 待联调 |
| 优惠券 | `POST /voucher`、`POST /voucher/seckill`、`GET /voucher/list/{shopId}` | 待联调 |
| 秒杀订单 | `POST /voucher-order/seckill/{id}` | 待联调 |
| 图片上传 | `POST /upload/blog`、`GET /upload/blog/delete?name=` | 待联调 |
| 评论 | 目前 Controller 未定义路由 | 不属于当前可调用接口 |

## 已实际执行

| 日期 | 命令/方式 | 结果 | 边界 |
|---|---|---|---|
| 2026-10-23 | `./mvnw test` | 通过，2 tests、0 failures、0 errors | 只有 `UserContextTest` 两项单元测试 |
| 2026-10-23 | `./mvnw verify` | 通过，完整 10 模块 Maven reactor | Maven 仍只执行上述 2 项自动化单测 |
| 2026-10-23 | `./mvnw -DskipTests compile` | 通过 | 只证明源码可编译 |
| 2026-10-23 | `python scripts/check_api_surface.py` | 通过，55 个 HTTP mappings | 只核对 Controller 路由声明数量；不等于接口功能调用通过 |
| 2026-10-23 | Playwright + Microsoft Edge 打开 11 个静态页面 | 11 页面 HTTP 200、0 pageerror | 页面触发的后端请求目前因 Gateway/服务依赖未启动返回 502；不代表页面业务操作通过 |
| 2026-10-23 | 隔离 MySQL 导入点评脚本 | 11 tables、14 shops | 仅验证数据库脚本能在 MySQL 8.0.39 非严格 sql_mode 下导入；不是业务 API 验证 |

## 全量接口联调状态

全量接口测试尚未完成。需要启动 Redis、Nacos、RabbitMQ、Gateway、6 个电商服务和本地生活服务，准备各自 schema、Nacos shared 配置、JWT 密钥及测试账号。每个接口的正常、缺参/非法参数、未登录/权限、边界/并发场景都需记录响应和副作用后才能标为通过。秒杀接口还需隔离库存和重复购买用例。
