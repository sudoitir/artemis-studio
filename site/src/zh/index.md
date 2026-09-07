---
layout: home
title: Artemis Studio
titleTemplate: 一个控制台，管理你所有的 Artemis 集群
head:
  - - meta
    - name: description
      content: 面向 Apache ActiveMQ Artemis 的开源集群级管理与可观测性工具——实时主备拓扑、跨节点队列、安全的消息操作、请求-应答追踪，以及对消息的 SQL 查询。

hero:
  name: Artemis Studio
  text: 一个控制台，管理你所有的 Artemis 集群
  tagline: 实时拓扑、跨节点队列、安全的消息操作，以及对消息的 SQL 查询——一个实例覆盖你运行的每一个 Apache ActiveMQ Artemis 集群。
  image:
    src: /img/topology.png
    alt: 集群的实时主备拓扑
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/quickstart
    - theme: alt
      text: 它是什么
      link: /zh/guide/
    - theme: alt
      text: GitHub
      link: https://github.com/sudoitir/artemis-studio

features:
  - title: 面向整个集群，而不是单个 Broker
    details: 主备节点对、复制状态与共享 NodeID 轴，全部呈现在一张画布上。HA 角色来自对每个节点的轮询，绝不读取配置文件——一对节点同时为 live 会触发脑裂告警，而不是在凌晨三点变成意外。
    link: /zh/guide/
  - title: 所有节点的所有队列，同一张表
    details: 队列、地址、消费者、会话、连接与生产者，跨全部节点汇入一张虚拟化表格，通过 SSE 实时更新并标注来源节点。按堆积量排序，集群里最糟糕的那一行就在最上面。
    link: /zh/guide/
  - title: 用 SQL 查询你的消息
    details: “订单 4471 去哪了？”只需要一条跨集群所有队列的查询——包括 JMS selector 根本看不到的消息体。查询的代价在执行之前就已明示。
    link: /zh/guide/sql-console
  - title: 值得信任的破坏性操作
    details: 每一个变更调用都支持 ?dryRun=true，只返回受影响数量而不执行。清空与删除必须手动键入队列名称。所有操作都与命令在同一事务中写入审计记录。
    link: /zh/guide/
  - title: 请求-应答追踪
    details: 跨地址、跨节点地把请求与其应答关联起来，并按你声明的预期统计延迟与超时。Broker 时钟会被归一化到 Studio 的时钟上，偏差公开可见。
    link: /zh/guide/
  - title: 与人共用一套权限的 MCP 服务器
    details: 让助手针对你真实的集群回答“为什么 ORDERS.DLQ 堆积了”——通过十余个意图化工具，遵循与人完全相同的权限与审计链路。
    link: /zh/guide/mcp
---

## 看它跑起来

![Artemis Studio：拓扑、跨节点队列表格，以及一条进入死信队列的消息](/img/demo.gif)

一次针对真实双主备集群的真实会话：拓扑视图、跨节点队列表格，以及一条被投递到死信队列的消息。

## 一条命令装好

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up          # Studio + Postgres，自动生成密钥，并固定到最新发布版本
```

你的 Broker 本来就存在——Studio 只负责纳管，不会去启动它们。除了你几乎肯定已经开启的管理端点之外，它不需要你改动 `broker.xml`。[完整快速开始 →](/zh/guide/quickstart)

::: warning Alpha 阶段
仍在积极开发中，功能尚未完备。已发布的镜像都是预发布的 dev 构建（`sudoit1/artemis-studio:dev`，暂无 `:latest`）。请预期会有破坏性变更。
:::
