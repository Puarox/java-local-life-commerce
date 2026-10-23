# 评测记录

## 当前验证

| 检查项 | 结果 | 说明 |
|---|---|---|
| Maven Wrapper | 通过 | Maven 3.9.9，Wrapper 从固定 URL 下载 |
| 全仓编译 | 通过 | `mvnw.cmd -DskipTests compile`，9 个服务模块 |
| 单元测试 | 通过 | 2 个共享身份上下文测试，0 失败；不需要 Redis/MySQL/Nacos |
| 全量打包 | 通过 | `mvnw.cmd clean package` 成功；已确认 9 个生成 JAR 都不包含 `hmall.jks` |
| 多服务联调 | 未执行 | 当前机器没有 Docker，需准备 Nacos、MySQL、Redis、RabbitMQ 及各自 schema/config |
| 压力测试 | 未执行 | 不填写吞吐、延迟、成功率等简历指标 |

## 本地验证步骤

在仓库根目录执行：

```powershell
.\mvnw.cmd -v
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd test
.\mvnw.cmd package
```

多服务联调前须准备项目所需数据库和 Nacos 配置。原始点评 SQL 会删除并重建同名表，只能用于全新、可丢弃的本地 schema。确保秒杀订单 `(user_id, voucher_id)` 唯一索引存在。

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

测试数据应能回到具体提交、机器、配置和工具命令。源码规模和模块数量属于静态统计，不能与运行压测数据混写。
