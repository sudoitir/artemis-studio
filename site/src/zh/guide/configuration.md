---
title: 配置
description: Artemis Studio 读取的环境变量、哪些是必需的，以及它维护的两个配置平面。
---

# 配置

## 环境变量

| 变量 | 必需 | 说明 |
|---|---|---|
| `ARTEMIS_STUDIO_DB_URL` | 是 | `jdbc:postgresql://host:5432/artemis_studio` |
| `ARTEMIS_STUDIO_DB_USER` / `_DB_PASSWORD` | 是 | — |
| `ARTEMIS_STUDIO_SECRET_KEY` | 是 | 用于加密存储的 Broker 凭据。必须是**恰好 32 字节**的 Base64，否则应用不会启动：`openssl rand -base64 32` |
| `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY` | 否 | 用于解密 `studio_config_property` 中存放的 `{cipher}` 值。这是与 `ARTEMIS_STUDIO_SECRET_KEY` **不同**的一把密钥——不要复用 |
| `JAVA_OPTS` | 否 | 默认为 `-XX:MaxRAMPercentage=75` |

`ARTEMIS_STUDIO_SECRET_KEY` 无法就地轮换：它是所有已存储 Broker 凭据的加密密钥。丢失它意味着需要重新录入每一个连接的凭据。

## 两个平面

配置被刻意拆成两半，划分依据是"谁在什么时候改这个值"。

**运维平面**——`studio_setting`——存放运维人员在 Studio 运行期间调整的内容：轮询节奏、保留窗口、批量操作上限、SQL 控制台的成本上限。改动即时生效、写入审计，且无需重启。每一个可由设置调节的调度都是一个会重新读取配置的触发器，而不是固定注解，这正是节奏变更能立刻生效的原因。

**部署平面**——Spring Cloud bootstrap 属性——存放属于这次部署的内容：数据源、密钥、OIDC issuer。这里的值可以以 `{cipher}` 形式存储，并由 `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY` 解密。系统中没有配置中心。

原因见 [ADR-0047](/reference/adr/0047-two-configuration-planes)，调度如何感知设置变更见 [ADR-0048](/reference/adr/0048-settings-driven-dynamic-schedules)（英文）。

## 数据库

PostgreSQL，模式由 Liquibase 拥有，并在启动时迁移。Studio 在启动时会针对迁移后的模式校验映射，而不是生成 DDL，因此漂移的模式会大声失败，而不是悄悄出错。

Postgres 拥有配置、用户与审计链路。来自 Broker 的表——`queue_snapshot`、`metric_sample`——是**可丢弃的缓存**：丢了它们只会损失历史，绝不会损失真相。

## Broker 连接

通过界面注册，而不是通过环境变量。一个连接保存种子管理端点与凭据，并加密存储。主传输是 Jolokia HTTP；Artemis Core 客户端是第二通道，用于通知与忠实的消息 I/O，依赖它的功能会以它是否可达为门控——而且是可见地门控，并附上可以启用它的 `broker.xml`。

见 [ADR-0002](/reference/adr/0002-broker-transport-and-capability-model)（英文）。
