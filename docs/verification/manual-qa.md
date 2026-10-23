# 本地运行与人工验收

更新时间：2026-10-23

## 已验证环境

- Java 17 运行 Spring Boot 2.7.12 项目；源码按 Java 11 语言级别编译。
- MySQL 8.0.39 隔离实例：`127.0.0.1:3307`，数据目录 `F:\intern2026\.local-runtime\mysql-test`。点评 schema 有 11 张表及 14 个样例商户；电商各服务使用独立 schema。
- Redis 3.2.100：`127.0.0.1:6379`；Nacos 2.2.3 standalone：`127.0.0.1:8848`；RabbitMQ 3.13.7：`127.0.0.1:5672`。
- Spring 服务：Gateway `8080`、商品 `8081`、用户 `8082`、购物车 `8083`、订单 `8086`、支付 `8087`、本地生活 `8088`。
- 静态前端 `http://127.0.0.1:8090/`。服务与依赖日志写入 `.local-runtime\service-logs`（运行时目录在仓库同级的 `F:\intern2026` 下，不进 Git）。
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

`start-services.ps1` 会启动七个 Spring 可执行 JAR，并将本地生活服务端口覆写到 8088。若 JAR 不存在，先执行 `.\mvnw.cmd -DskipTests install`。前端需另开终端运行；若 8090 已有监听则无需重复启动。停止由项目脚本启动的 Java 业务服务：

```powershell
.\scripts\stop-services.ps1
```

依赖服务可在任务结束时通过对应进程/服务管理器停止；不要停止或改写机器原有 MySQL80（3306）。所有项目服务只绑定本机地址，测试脚本会拒绝远程 Gateway。

## IDEA 快速核验

1. IDEA 选择 **Open**，打开仓库根目录的 `pom.xml`，以 Maven 项目导入；Project SDK 选 JDK 17，Maven Wrapper 选项目自带 wrapper。
2. 等 Maven 识别 9 个业务/公共模块后，展开 `local-life-service`，确认 `UserController.info` 与 `BlogCommentsController` 路由能被索引；运行根目录 Maven 的 `test` 生命周期。
3. 服务已启动时不要在 IDEA 再起第二份同端口服务。打开 <http://127.0.0.1:8090/login2.html> 登录演示账号，回到个人页检查头像/昵称/简介为空时的显示，再打开编辑资料页确认资料能读取；本项目当前个人资料页只支持读取展示，尚无资料更新 API。
4. 打开 <http://127.0.0.1:8090/commerce.html>，搜索商品、加购、创建订单并余额支付；在订单完成后检查支付结果和状态。
5. 打开笔记详情页，查看评论列表并新增一条评论；再在 Network 面板确认请求通过 `/api` 到 Gateway。评论列表只显示未隐藏评论。
6. Gateway/Nacos/业务服务运行日志位于 `.local-runtime\service-logs`。若 IDEA 启动某个 Spring 服务失败，先检查对应日志、端口占用、JDK 及 `JWT_KEYSTORE_LOCATION` / `JWT_KEYSTORE_PASSWORD` 环境变量。

## 自动化验证记录

| 日期 | 命令/动作 | 结果 |
|---|---|---|
| 2026-10-23 | `python scripts/check_api_surface.py` | 56 个 HTTP mappings |
| 2026-10-23 | `python scripts/test_http_integration.py` | 58 次真实 Gateway HTTP 调用通过；包含下单、RabbitMQ 支付通知、秒杀下单、上传和删除 |
| 2026-10-23 | `python scripts/test_frontend_smoke.py` | 12 页面 HTTP 200，无页面级 JavaScript 错误 |
| 2026-10-23 | `python scripts/test_commerce_browser.py` | Edge 完成商品搜索、购物车、下单、余额支付和订单状态检查 |
| 2026-10-23 | `.\mvnw.cmd -q -DskipTests install` | 通过；10 个 Maven reactor 项目 |

浏览器测试依赖安装到仓库外隔离目录：`python -m pip install --target F:\intern2026\.local-runtime\python-packages -r scripts/requirements-test.txt`，再设置 `$env:PYTHONPATH` 指向此目录。脚本会自动寻找 Edge；如未自动找到，可设置 `EDGE_EXECUTABLE`。

## 测试账号及数据边界

集成测试用来源 SQL 中的 Jack 演示账号（密码 `123`），测试写入隔离数据库，不要把测试地址改为远程环境。验证码由本地 Redis 保存，未接短信供应商。图片上传目录默认 `frontend/public/imgs`，自动化上传用例会删除测试图片。测试可能留下业务记录，应在可丢弃的开发库执行。

## 未验证范围

目前没有可报告的吞吐、延迟、成功率或超卖压测指标。Seata 仍显式禁用；目前实际运行验证的是 Nacos 注册与配置、Gateway/Feign 调用、Redis 缓存、RabbitMQ 支付结果通知等分布式链路，不应写成 Seata 全局事务已验证。资料更新、评论隐藏/管理以及异常参数的完整边界矩阵尚未实现或测试。
