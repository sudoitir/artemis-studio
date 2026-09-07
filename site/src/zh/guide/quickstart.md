---
title: 快速开始
description: 用已发布的镜像运行 Artemis Studio 与它的 Postgres，注册第一个集群并登录。
---

# 快速开始

Studio 需要两样东西：一个 PostgreSQL 数据库，以及能够访问你的 Broker 管理端点的网络连通性。你的 Artemis 集群本来就存在，通过界面注册即可——这里没有任何东西会启动一个 Broker。

::: warning Alpha 阶段
已发布的镜像都是预发布的 dev 构建（`sudoit1/artemis-studio:dev`，尚未发布 `:latest`）。版本之间可能出现破坏性变更。
:::

## 从克隆仓库开始，使用 `just`

[`just`](https://github.com/casey/just#packages) 是本仓库使用的任务运行器。单独执行 `just` 会分组列出全部任务。

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up
```

`just up` 会先执行 `just setup`：在干净的检出上，它会写入 `deploy/compose/.env`，其中包含随机生成的 `SECRET_KEY` 与 `DB_PASSWORD`，并把 `STUDIO_IMAGE` 固定到最新的发布标签。随后启动 Studio 与 Postgres 并等待其就绪。打开 <http://localhost:8080>。

之后再次运行 `just setup` 即可把版本固定向前推进。

## 不使用 `just`

```bash
base=https://raw.githubusercontent.com/sudoitir/artemis-studio/main/deploy/compose
curl -sO "$base/compose.prod.yaml"
curl -s "$base/.env.example" -o .env   # 然后按"配置"一节编辑它
docker compose -f compose.prod.yaml --env-file .env up -d
```

或者，针对你已有的 Postgres 直接跑一个容器：

```bash
docker run -p 8080:8080 \
  -e ARTEMIS_STUDIO_DB_URL=jdbc:postgresql://db:5432/artemis_studio \
  -e ARTEMIS_STUDIO_DB_USER=artemis_studio \
  -e ARTEMIS_STUDIO_DB_PASSWORD=... \
  -e ARTEMIS_STUDIO_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/artemis-studio:dev
```

## 首次登录

用户名是 `admin`。针对空数据库的首次运行会生成密码，并**仅打印一次**到容器日志：

```bash
docker compose -f compose.prod.yaml logs studio | grep -A4 'Created administrator'
```

首次登录时会强制你设置新密码。如果在修改之前弄丢了这个初始密码，唯一的恢复办法是直接在 Postgres 中重置对应行——目前还没有找回密码的流程。

## 注册一个集群

登录后进入 **Clusters → Add**。你只需提供一个种子节点的管理端点及其凭据；拓扑中的其余部分会从 Broker 自身发现。凭据以 `ARTEMIS_STUDIO_SECRET_KEY` 加密存储。

如果某项能力缺失——Core 客户端不可达，或某个管理操作未暴露——Studio 会告诉你缺的是哪一项，并给出可以启用它的确切 `broker.xml` 片段，而不是把功能藏起来。

## 部署在反向代理之后

界面的实时流是 `GET /api/v1/stream` 上的 Server-Sent Events，Studio 前面的代理**绝不能对它做缓冲**：

- nginx —— 对该路径设置 `proxy_buffering off;`
- Apache —— 对该路径关闭输出缓冲
- Traefik —— 无需改动即可工作

否则拓扑图和队列表格只会在 5 秒轮询时更新，看起来就像一个半死不活的产品。

## 先拿一次性的 Broker 试试

如果你想在把它接到任何真实系统之前先看看效果，仓库自带一套开发栈：从源码构建 Studio，启动两对真实的 Artemis 主备节点，并灌入看起来像真实生产环境的流量——包括一个没有消费者、堆积持续增长的地址，一份真实的死信积压，以及一个被停掉的节点。

```bash
just dev-up                          # Postgres + 一对 Artemis + Studio
ADMIN_PASSWORD=… just demo           # 第二对节点，加上真实流量
```

这套种子脚本不会直接写 `metric_sample` 或 `queue_snapshot`——流量是通过 Broker 自带的 CLI 真实生产与消费的，所以图表是测量结果，而不是编造的。

## 接下来

- [配置](/zh/guide/configuration)——它读取的每一个变量
- [SQL 控制台](/zh/guide/sql-console)——提出一个跨队列的问题
- [MCP 服务器](/zh/guide/mcp)——把同样的能力交给助手
