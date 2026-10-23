# Source Inventory and Migration Baseline

Captured on 2026-10-23 (Asia/Shanghai). Source repositories remain untouched by this migration.

## Spring Cloud commerce source

- Repository: `F:\Course_extra\SpringCloud\project\hmall_springCloud`
- HEAD: `9f4be75162cfafdc82b3b69ed43441a3cdb6113f` (`main`, aligned with `origin/main` at capture)
- Working tree: modified `hmall/pay-service/.../PayOrderServiceImpl.java`; untracked aggregate POM, `hm-common` source/POM, `hm-service`, IDE/build/log output, and module targets.
- Imported modules: `hm-common`, `hm-api`, `hm-gateway`, `item-service`, `cart-service`, `user-service`, `trade-service`, `pay-service`.
- Excluded from runtime migration: `hm-service`, the legacy monolithic duplicate; generated targets, logs, and IDE files.
- Stack baseline: Spring Boot 2.7.12, Spring Cloud 2021.0.3, Spring Cloud Alibaba 2021.0.4.0, Java source level 11, MyBatis Plus 3.5.6.
- Main concepts: Nacos discovery/config, Gateway, OpenFeign, Sentinel, Seata, RabbitMQ, MyBatis Plus, MySQL.

## HM Dianping source

- Repository: `F:\Java\Project\comment\backend\hm-dianping`
- HEAD: `911d6fc80f5fdbafd16c4bb9a56b8975a7414b16` (`master`, aligned with `origin/master` at capture)
- Working tree: modified `pom.xml`, shop type/user/voucher services and voucher-order service; staged and modified Redisson/lock files.
- Imported: 71 main Java files, application configuration, and the SQL schema/data script. The original Spring context test was replaced by a dependency-free shared-context unit test because it launched an unbounded Redis ID-generation workload during every Maven test run.
- Stack baseline: Spring Boot 2.3.12, Java 8, MyBatis Plus 3.4.3, MySQL Connector 5.1.47, Redis, Redisson 3.13.0.
- Main concepts: merchant cache, cache rebuild/penetration controls, blogs/comments, follows/Feed, voucher ordering, Redis ID generation.
- The captured source revision contains incomplete voucher-order behavior (including a hardcoded user identifier and duplicated stock path). The integrated implementation derives identity from the shared user context and has one transactional stock decrement; database-backed concurrency behavior still requires runtime integration testing.

## Migration rules

1. Keep the source checkouts and their user changes intact.
2. The new repository is the only destination for integration edits and commits.
3. Do not copy generated build output, IDE settings, logs, local credentials, or machine-specific absolute paths.
4. Every migrated capability must be linked to its source module and a verification record.
5. New commits document actual coherent changes; they do not rewrite or fabricate source history.
