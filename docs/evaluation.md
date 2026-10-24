# 评测记录

记录更新时间：2026-10-24。测试以当前工作区源码为对象，提交前需用新 commit hash 替换“工作区未提交”标记。

## 功能与构建验证

| 检查项 | 结果 | 说明 |
|---|---|---|
| Maven 构建 | 通过 | `mvnw.cmd -q -DskipTests install`；10 个 Maven reactor 项目 |
| Controller 路由盘点 | 通过 | `python scripts/check_api_surface.py`，56 个 HTTP mappings |
| 全量 API 联调 | 通过 | `python scripts/test_http_integration.py`，59 次 HTTP 调用；覆盖所有映射，包含错误支付密码的 400 响应和支付消息更新订单状态 |
| 前端静态与运行检查 | 通过 | 12 页面 Edge 无 pageerror；电商浏览器流完成搜索、加购、余额支付 |
| 消息队列链路 | 通过 | RabbitMQ 收到支付结果后，订单状态由待支付更新为已支付 |
| 其他分布式能力 | 部分通过 | Nacos 注册/配置、Gateway 路由和 Feign、Redis 缓存/锁、RabbitMQ 已在本地联调；Seata 保持禁用，没有验证全局事务 |
| 压力测试 | 未执行 | 不填写 QPS、P95/P99、成功率或并发超卖数字 |

## 运行复现

使用 `docs/verification/manual-qa.md` 启动隔离 MySQL、Redis、Nacos、RabbitMQ、七个 Spring 服务和前端。接口脚本只允许本机 Gateway，且会写开发数据库。浏览器测试依赖安装在项目外部的 `.local-runtime\python-packages`，避免将第三方浏览器运行时提交到 Git。

## 简历数据边界

- 可以陈述：接口路由数量（静态盘点）、真实接口覆盖结果（以本记录和脚本输出为准）、具体实现的业务能力与技术方案。
- 不可陈述：未经单独压测的吞吐量、延迟、可用性或并发库存正确性结论；Seata 全局事务已经运行；资料编辑已实现。
- 压测前需补充：测试 commit、机器/JDK/JVM、服务实例和数据规模、工具与请求模型、预热和持续时间、请求/错误统计、延迟分位、库存一致性检查及原始报告路径。

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
