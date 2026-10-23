# 本地运行与人工验收

更新时间：2026-10-23

## 当前验证结论

- `./mvnw test`：通过，现有自动测试共 2 项（`UserContextTest`）。这不是全接口联调结论。
- `./mvnw -DskipTests compile`：通过。
- 完整接口联调尚未完成。服务依赖（Nacos、Redis、RabbitMQ、多个 MySQL schema）目前未全部配置；不要把单元测试或编译结果描述成所有接口已通过。
- 点评静态页面已迁入 `frontend/public`。启动方式和页面/API 兼容修复在本次变更中；请按本文的流程验收。

## 前端启动

1. 在仓库根目录启动后端依赖、Nacos、Gateway 和需要的业务服务。Gateway 默认端口 `8080`。
2. 在另一个 PowerShell 窗口执行：

   ```powershell
   node frontend/server.js
   ```

3. 打开 <http://127.0.0.1:8090/>。本地静态服务器负责页面与资源，`/api/*` 代理到 Gateway `http://127.0.0.1:8080/*`。
4. 如果 Gateway 不是本机 8080：

   ```powershell
   $env:GATEWAY_URL = 'http://127.0.0.1:8080'
   $env:FRONTEND_PORT = '8090'
   node frontend/server.js
   ```

## 页面人工操作流程

1. **登录**：打开 `/login.html`，输入手机号，点“发送验证码”。从 `user-service` 日志查看开发验证码（只记录手机号后四位，不会打印验证码）；在当前实现中需在 Redis 查询 `login:code:<手机号>`，两分钟内输入并登录。也可打开 `/login2.html` 使用已存在的用户名和密码。
2. **首页/笔记**：登录后查看首页分类与热门笔记，打开笔记详情；在浏览器开发者工具的 Network 中确认请求走 `/api`，并检查 `GET /blog/hot`、`GET /blog/{id}` 和点赞状态。
3. **商户浏览**：首页分类进入商户列表，打开商户详情，核对商户信息与优惠券列表。
4. **个人页**：点击个人页，检查 `/user/me`、`/user/info/{id}`、`/blog/of/me`；切换“关注”页，滚动加载并检查 `/blog/of/follow` 是否持续返回新内容。
5. **关注/他人主页**：从笔记进入作者页，关注和取消关注，检查按钮状态；查看共同关注及作者笔记。
6. **发布笔记**：从发布入口选择商户、上传图片、提交内容；确认响应中的图片地址能通过前端静态服务器读取，笔记进入本人列表。
7. **优惠券秒杀**：打开有券的商户详情，在测试用户账号上提交秒杀；检查重复提交、库存为 0 和正常下单三种情况。此操作会修改数据库和 Redis 测试数据，只使用隔离测试库。
8. **退出**：个人页退出后 token 应从 `sessionStorage` 清除并回到登录页；再次访问受保护 API 应收到 401。

## 环境与数据安全

- 本机原有 MySQL80（3306）服务正在运行，但 root 登录口令未知；没有修改或重置它。另已创建隔离 MySQL 8.0.39 测试实例，绑定 `127.0.0.1:3307`，并将点评 SQL 导入新建的 `local_life` schema（11 张表、14 个商户样例）。测试实例数据目录位于 `F:\intern2026\.local-runtime\mysql-test`。
- 本机尚未发现 Docker、Nacos、Redis、RabbitMQ 命令。需要真实联调前，先创建隔离的开发 schema 和服务配置；不要对现有数据库执行 `hmdp.sql`（脚本包含 `DROP TABLE`）。
- `local-life-service` 的图片目录可由 `IMAGE_UPLOAD_DIR` 环境变量覆盖，默认 `./frontend/public/imgs/`。
- 点评开发验证码目前仅写入 Redis，需从 Redis 查询；未接入短信供应商。

## 测试记录规则

每次运行记录日期、Git commit、执行命令、环境版本、测试总数/通过/失败、失败摘要与原始输出位置。性能指标必须在独立压测中实际测得；这里的接口人工验收数据不能替代压测数据。
