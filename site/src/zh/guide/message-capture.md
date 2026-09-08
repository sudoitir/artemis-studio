---
title: 消息捕获
description: 把一个 address 路由的每一条消息复制进 Studio 自己的、有环形上限的队列——它在 broker 上创建了什么、代价是什么、以及如何移除。
---

# 消息捕获

实时 tail 和消息索引回答的问题，都比它们看起来能回答的更弱。两者都是**轮询**：每隔几秒浏览一次队列，记录那一瞬间还停在上面的东西。消耗速度快于轮询间隔的队列是看不见的——而大多数健康的队列，大多数时候，正是如此。

捕获填上了这个缺口。它按队列显式开启，并且会改变 broker 的路由，所以本页明确说明它创建了什么，以及它如何被移除。

## 它是什么

一个**非独占（non-exclusive）divert**，把某个 address 路由的每一条消息复制进一个由 Studio 拥有并消费的队列。你的消费者不受影响：非独占 divert 只复制，不夺走。Studio 只消费自己的那份副本。

```
ORDER.IN ──non-exclusive divert──► artemis-studio.capture.<instance>.ORDER.IN.<subscription>
   （每个节点）                        ring-size N · address-full-policy=DROP
                                      expiry-delay · 非持久化
                                             │
                                        Core 消费者
                                             │
                              实时 tail · 消息索引 · 请求-应答
```

## 它在每个存活节点上创建什么

| 对象 | 为什么 |
|---|---|
| 源 address 上的 `divert`，**非独占** | 副本本身。生产路由不变。 |
| 一个**非持久化、有环形上限的队列** | 存放副本。`ring-size` 意味着 broker 丢弃最旧的，而不是无限增长。 |
| `artemis-studio.capture.#` 上的 `address-setting` | `address-full-policy=DROP`，使 broker 绝不会因 Studio 而阻塞或分页；再加 `expiry-delay`，使被遗弃的 tap 在时间上也有界。Artemis 自己的文档就警告不要对持有环形队列的 address 分页，所以这是 tap 的一部分而非可选项。 |
| `artemis-studio.capture.#` 上的 `security-setting` | 把消费该队列的权限限制到 Studio 自己的 broker 角色。捕获队列是生产载荷完整的第二份副本；不加保护地创建它，等于把一个监听口交给每一个通过 broker 认证的客户端。 |

Studio 连接所持有的角色是配置项——`artemis-studio.capture.broker-role`，默认 `amq`。Studio 无法自行发现它：broker 没有提供"我是谁"的读取接口。

## 它不会自己消失

通过管理 API 创建的 divert、address setting 和 security setting **会在 broker 重启后继续存在**。这是在 Artemis 2.56.0 上实测得出的，不是假设——见 [ADR-0065](/reference/adr/0065-runtime-broker-configuration-persists-across-restarts)。

因此移除永远是一个明确的动作：

- 在 Studio 中**删除订阅**。它会从安装过的每个节点上移除 divert、队列、address setting 和 security setting。
- 如果 Studio 本身已经不在了，环形上限与过期延迟限制了代价：队列最多持有 `ring-size` 条消息，且没有一条比过期延迟更旧，队列本身也不写日志。这是一个固定、有界的代价——但需要你来移除。

## 它会拒绝做什么

两项预检，都是因为另一种结果会静默失败：

- **源 address 上已存在独占 divert。** Artemis 会先应用独占 divert，所以排在它后面的捕获 divert 永远看不到消息——它会干净地安装好却什么都不记录，与"队列很安静"无法区分。捕获会被拒绝，并指出那个 divert，以及流量实际去往的 address。
- **没有权限限制捕获队列。** 拒绝，并给出可以授权的 `security-setting`。

## 一个捕获结果声称了什么

捕获是**以 address 为范围的**。divert 在 address 路由时复制，早于 multicast 扇出，所以对于绑定了多个队列的 address，索引中每条被路由的消息只有一行，并且确实无法说出是哪个订阅收到了它。控制台会在适用的结果上说明这一点。anycast 不受影响——address 与队列重合。

捕获也是**按节点的**。tap 是节点本地对象，被提升的备份节点在 Studio 下一次协调之前不会有 tap。这个缺口会被记录而不是被平均掉：订阅按节点报告状态，而从未捕获节点读取的查询会说明这一点。

被捕获的行可以用**原始**队列名查询——从捕获队列到源 address 的映射是 Studio 自己的状态，而不是某个 broker 头部。`_AMQ_ORIG_MESSAGE_ID` 在此之上提供源消息 id；当 broker 没有复制它时，"在 broker 上核实"会以禁用状态并说明原因呈现，而不是被隐藏。

## 什么限制了它

| 上限 | 默认值 | 到达上限时 |
|---|---|---|
| `ring-size` | 10,000 | broker 丢弃最旧的。丢弃数会被估算并按节点报告。 |
| `expiry-delay` | 24 小时 | broker 使其过期。`auto-create-expiry-resources` 关闭，所以不会有任何堆积。 |
| 摄入速率 | 每个订阅 500/秒 | 超速的消息被丢弃并计数，因此一个洪流不会饿死其他订阅。 |
| 消息体大小 | 每条 256 KiB | 截断存储并标记，绝不整条搬进 Postgres。 |
| 保留期 | 7 天 | 丢弃分区。 |
| 存储大小 | 每个订阅 5 GiB | 订阅降级并说明原因。 |

可选的**捕获过滤器**——一个 Artemis 过滤表达式——同时收窄 broker 复制的内容与 Studio 存储的内容，对两者都不增加成本。

## 权限

开启和关闭捕获需要 `capture:write`。这刻意不是 `settings:write`：捕获会按计划改变 broker 路由，并创建生产载荷的第二份副本，这与调整 Studio 的轮询频率是不同量级的权限。每一次安装与移除都在调用 broker 之前写入审计，并在得到结果后更新。

## 决策

- [ADR-0062](/reference/adr/0062-message-capture-is-a-divert-into-a-ring-bounded-queue) — 机制
- [ADR-0065](/reference/adr/0065-runtime-broker-configuration-persists-across-restarts) — 为什么移除必须是明确动作
- [ADR-0059](/reference/adr/0059-message-index-is-opt-in-and-disposable) — 捕获写入的那个索引
