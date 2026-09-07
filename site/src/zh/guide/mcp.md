---
title: MCP 服务器
description: Artemis Studio 支持 Model Context Protocol，让助手在与人完全相同的授权与审计链路下，针对你真实的集群作答。
---

# MCP 服务器

Studio 支持 [Model Context Protocol](https://modelcontextprotocol.io)，因此助手可以针对你真实的集群回答*"为什么 `ORDERS.DLQ` 堆积了"*，而不是靠猜。

它暴露的是十余个**意图化**工具——`cluster_health`、`diagnose_queue`、`queue_action`——而不是 REST API 的镜像。镜像会把模型的上下文耗在管道细节上，再把诊断工作丢回给它；这里的工具是按问题的形状设计的（[ADR-0045](/reference/adr/0045-mcp-server-is-a-capability-surface)，英文）。

## 获取密钥

登录 → 头像菜单 → **Account** → **API keys** → **New key** → 选择它携带的范围与权限 → 复制。密钥只显示一次。

## 接入

`POST /mcp`，与界面同源，密钥作为 bearer token。

```json
{
  "mcpServers": {
    "artemis-studio": {
      "type": "http",
      "url": "https://studio.example.com/mcp",
      "headers": { "Authorization": "Bearer as_..." }
    }
  }
}
```

不用客户端也能冒烟测试：

```bash
curl -s https://studio.example.com/mcp \
  -H "Authorization: Bearer as_..." \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 都有什么

| 类型 | 名称 | 用途 |
|---|---|---|
| Tool | `cluster_health` | 每个节点的 HA 角色、脑裂、复制延迟、正在触发的告警 |
| Tool | `list_resources` | 队列、地址、消费者、会话、连接、生产者 |
| Tool | `diagnose_queue` | 一个队列的全链路：堆积、趋势、消费者、DLQ、事件 |
| Tool | `metric_series` | 单个指标的分桶时间序列 |
| Tool | `config_diff` | 两个节点之间已分类的配置差异 |
| Tool | `browse_messages` / `message_body` | 先看消息头，再按 id 取单条消息体 |
| Tool | `trace_request_reply` | 流、延迟与超时统计、已配置的预期 |
| Tool | `activity_log` | Broker 事件，或 Studio 自身的审计链路 |
| Tool | `queue_action` | 移动 / 重投 / 删除 / 过期 / 清空 |
| Tool | `send_message` | 投递一条消息 |
| Tool | `alert_rule` / `studio_setting` | 告警规则；运维设置 |
| Resource | `studio://clusters`、`studio://permissions` | 这把密钥能看到什么、能做什么 |
| Resource | `cluster://{id}/topology`、`cluster://{id}/capabilities` | 节点；连接支持什么，以及启用其余部分所需的 `broker.xml` |
| Prompt | `triage_cluster`、`investigate_queue`、`before_you_purge`、`tune_scrape_load` | 运行手册 |

## 安全契约

一个握有管理 API 的助手，正是安全模型必须写得枯燥而明确的地方。这里就是：

- **密钥永远不会超过它的所有者。** 每次调用都会与所有者*当下*的授权取交集，所以收紧一个人的权限会立刻收紧他的所有密钥（[ADR-0046](/reference/adr/0046-mcp-authenticates-with-existing-api-tokens)，英文）。
- **变更默认 dry-run。** 真正的破坏性执行还额外要求 `confirm` 等于队列自身的名称——这与批量上限的 `override` 是两个不同的问题，`confirm` 永远无法满足 `override`。
- **一切都会被审计**，记在所有者名下并附上密钥名称（`ada [token: laptop-agent]`），dry run 也不例外。
- **对于密钥没有授权的集群**，返回的是*"不存在这样的集群，或这把密钥对它没有授权"*——既不点名缺了哪项权限，也不确认这个 id 是否存在。

## 发现机制

`studio_help` 是一个工具，而不是一个可选资源；并且由同一份目录生成 schema、帮助文本、资源与服务器说明——所以它们不可能相互矛盾（[ADR-0054](/reference/adr/0054-mcp-discovery-is-a-tool-not-a-resource)，英文）。
