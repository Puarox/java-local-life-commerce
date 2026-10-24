# 评测记录

记录更新时间：2026-10-24。测试针对本仓库当前源码；具体版本以验证时的 Git commit 为准。

## 功能与构建验证

| 检查项 | 结果 | 说明 |
|---|---|---|
| Maven 构建 | 通过 | `mvnw.cmd -q -DskipTests install`；10 个 Maven reactor 项目 |
| Controller 路由盘点 | 通过 | `python scripts/check_api_surface.py`，72 个 HTTP mappings（含内部 Feign 路由） |
| 主要 API 联调 | 通过 | `python scripts/test_http_integration.py`，87 次 HTTP 调用；覆盖主要业务路由，包含余额支付、取消、核销、退款及幂等拒绝 |
| 前端静态与运行检查 | 通过 | 13 页面 Edge 无 pageerror；活动页显示有效券，门店页显示真实笔记；电商浏览器流完成搜索、加购、余额支付 |
| 支付通知可靠性 | 通过（隔离环境） | MySQL outbox 与扣款/支付状态在 Seata 事务中提交；RabbitMQ confirm/return、退避重试及定时业务状态对账；注入 NO_ROUTE 和业务订单滞后后自动恢复 |
| 券履约 | 通过（主要路径） | 待支付取消并关闭支付单；支付后生成核销码；管理员核销；未核销退款到账；重复操作和已核销退款被拒绝 |
| 商品下单跨库事务 | 通过 | Seata 1.5.2 AT；库存不足时 HTTP 400，交易订单/明细不变、购物车恢复、库存不变；TC 记录全局回滚 |
| 其他分布式能力 | 部分通过 | Nacos 注册/配置、Gateway/Feign、Redis 缓存/锁、Seata 支付事务和 RabbitMQ 最终一致链路已联调 |
| 本机读接口压测 | 通过 | 10 并发、100 次预热、3000 次实测；3000 次成功；414.87 请求/秒，P95 38.25 ms，P99 50.98 ms |
| 秒杀并发正确性 | 通过（限定场景） | 同一账号 20 次并发抢库存 1 的券，1 次成功，剩余库存 0，订单 1 条 |
| 商户缓存互斥重建 | 通过（单元及本机集成冒烟） | `CacheClientTest` 32 并发请求只触发 1 次 DB 回源，另验证锁等待超时、中断语义、空结果负缓存；`python scripts/test_shop_cache_concurrency.py` 通过，真实本机 Redis/Gateway 的 50 个并发门店请求均成功，缓存已填充且锁键已释放。该冒烟不统计真实 DB 回源数，也不等同多实例 Redis 故障或性能测试 |
| 支付 Seata 回滚故障注入 | 通过（隔离环境） | `python scripts/test_payment_seata_rollback.py` 使 outbox 唯一键冲突；HTTP 500 后余额、支付单和业务单保持原值；清理夹具重试后扣款一次、outbox 一条、RabbitMQ 最终推进订单。TC 日志记录 XID `192.168.126.1:8091:9844403962945646` 全局回滚，结果见 `verification/results/payment-seata-rollback-20261024.json` |
| 多用户秒杀库存竞争 | 通过（本机隔离库，两档库存） | 20 个不同用户同步竞争，库存 1 时成功 1 单、库存 0；库存 5 时成功 5 单、库存 0；成功数与订单数相等，未超卖。原始响应见 `verification/results/seckill-multiuser-20261024-*.json`。这是正确性验证，不是 QPS 压测 |

## 运行复现

使用 `docs/verification/manual-qa.md` 启动隔离 MySQL、Redis、Nacos、RabbitMQ、Seata TC、七个 Spring 服务和前端。接口脚本只允许本机 Gateway，且会写开发数据库。浏览器测试依赖安装在项目外部的 `.local-runtime\python-packages`，避免将第三方浏览器运行时提交到 Git。

压测源码版本为 `0d1b1d85c33c8fde402cf4e68dd4bc37ca215ab8`。客户端为 Windows 11、20 逻辑处理器、15.8 GiB 内存，Python 3.13；服务使用 Java 17.0.12，Gateway 与本地生活服务各一实例。测试时点评库有 23 个商户、18 张券。请求模型为同机 Python 线程池对 Gateway `GET /voucher/active` 发起请求，每线程复用 HTTP 连接；实测耗时 7.231 秒，平均 23.99 ms、P50 22.41 ms、P95 38.25 ms、P99 50.98 ms。逐请求 CSV、JSON 汇总和秒杀并发响应明细保存在 [`results`](verification/results)。

## 简历数据边界

- 可以陈述：在商品下单链路使用 Seata AT 协调交易、购物车和库存，并通过库存不足注入验证全局回滚；本机活动列表读接口在上述条件下完成 3000 次请求、成功率 100%、约 415 请求/秒、P95 约 38 ms；同一账号对库存 1 的券进行 20 次并发请求，仅 1 次成功。
- 不可陈述：Seata 覆盖余额支付及 RabbitMQ 异步通知；单机读压测代表生产容量；同一账号并发用例证明多用户任意并发下永不超卖。若需要更强的性能或超卖结论，应增加多账号、多实例、持续时间、分档并发及异常注入实验。
- Redis 缓存可描述为“使用 Redisson 互斥重建并验证并发回源只执行一次、锁超时不误释放”。不要把单元替身测试描述为 Redis 集群/多实例压测；本地真实 Redis/Gateway 冒烟只用于检查集成和响应正确性。

## 压测记录模板

| 字段 | 记录 |
|---|---|
| Git commit |  |
| 测试时间/机器 |  |
| JDK / JVM 参数 |  |
| 服务实例数 |  |
| 数据库/Redis 配置 |  |
| 数据规模与初始化方式 |  |
| 压测工具及版本 |  |
| 并发用户/请求模型 |  |
| 预热与持续时间 |  |
| 请求总数/成功数/错误数 |  |
| 吞吐（QPS） |  |
| 延迟 P50/P95/P99 |  |
| 库存超卖/重复下单校验 |  |
| 报告及原始结果路径 |  |
