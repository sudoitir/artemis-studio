/**
 * What each address-setting key does, in one or two sentences, with an example
 * value — the explanation an operator reads before declaring it. Keyed by the
 * broker's JSON field name (the catalogue's `jsonName`, ADR-0067 D10). A key the
 * catalogue knows but this file does not still gets a field; it just has no
 * explanation beside it, which is stated rather than hidden.
 *
 * The wording follows the Artemis address-settings reference; units are the
 * broker's (bytes, milliseconds, messages).
 */
export interface KeyHelp {
  /** What the key governs and when it matters. */
  summary: string;
  /** A value an operator might actually declare, with its meaning. */
  example: string;
}

export const KEY_HELP: Record<string, KeyHelp> = {
  addressFullMessagePolicy: {
    summary:
      'What the broker does with new messages once the address reaches its size limit. PAGE writes them to disk and keeps accepting; BLOCK stalls producers; DROP discards silently; FAIL refuses with an error.',
    example: 'PAGE — keep accepting, spill to disk. The safe default for most addresses.',
  },
  maxSizeBytes: {
    summary:
      'The in-memory size (bytes) an address may reach before the full policy above applies. -1 means no limit and defers to the global limit.',
    example: '104857600 — 100 MiB in memory, then page or block.',
  },
  maxSizeMessages: {
    summary: 'The message count an address may hold in memory before the full policy applies. -1 disables the count limit.',
    example: '100000 — after a hundred thousand messages, page or block.',
  },
  maxSizeBytesRejectThreshold: {
    summary:
      'With the BLOCK policy, the size at which the broker stops blocking and rejects instead, so a producer that ignores flow control fails rather than hangs. -1 disables.',
    example: '209715200 — reject once twice the max size is queued.',
  },
  maxReadPageBytes: {
    summary: 'How many bytes of paged messages may be read back into memory at once for delivery.',
    example: '20971520 — 20 MiB read from the page files at a time.',
  },
  maxReadPageMessages: {
    summary: 'How many paged messages may be read back into memory at once. -1 leaves it to the bytes limit.',
    example: '-1 — bounded by max-read-page-bytes only.',
  },
  prefetchPageBytes: {
    summary: 'How many bytes of paged messages the broker reads ahead so delivery does not wait on disk.',
    example: '20971520 — 20 MiB read ahead.',
  },
  prefetchPageMessages: {
    summary: 'How many paged messages the broker reads ahead. -1 leaves it to the bytes value.',
    example: '-1 — bounded by prefetch-page-bytes only.',
  },
  pageSizeBytes: {
    summary: 'The size of each page file written when an address is paging. Must be smaller than max-size-bytes.',
    example: '10485760 — 10 MiB per page file.',
  },
  pageCacheMaxSize: {
    summary: 'How many page files are kept in memory to soften a switch back from paging.',
    example: '5 — five page files cached.',
  },
  pageLimitBytes: {
    summary:
      'A cap on the total size of the page files on disk. When it is reached the page-full policy applies. Unset means unlimited.',
    example: '10737418240 — stop paging past 10 GiB on disk.',
  },
  pageLimitMessages: {
    summary: 'A cap on the number of paged messages. When it is reached the page-full policy applies.',
    example: '1000000 — stop paging past a million messages.',
  },
  pageFullMessagePolicy: {
    summary: 'What happens to new messages once a page limit is reached: DROP discards them, FAIL refuses them with an error.',
    example: 'FAIL — producers see an error instead of silent loss.',
  },
  maxDeliveryAttempts: {
    summary:
      'How many times a message is redelivered after a consumer rolls it back before it goes to the dead-letter address. -1 retries forever.',
    example: '5 — dead-letter after the fifth failed delivery.',
  },
  messageCounterHistoryDayLimit: {
    summary: 'How many days of message-counter history the broker keeps for this address.',
    example: '10 — ten days of counters.',
  },
  redeliveryDelay: {
    summary: 'The wait (milliseconds) before a rolled-back message is redelivered. 0 redelivers at once.',
    example: '2000 — two seconds between attempts.',
  },
  redeliveryMultiplier: {
    summary: 'Multiplies the redelivery delay on each attempt, for exponential back-off. 1 keeps the delay constant.',
    example: '2.0 — 2 s, then 4 s, then 8 s.',
  },
  redeliveryCollisionAvoidanceFactor: {
    summary:
      'Adds a random spread to the redelivery delay, as a fraction of it, so many consumers do not retry at the same instant. 0 disables.',
    example: '0.15 — each delay varies by up to ±15 %.',
  },
  maxRedeliveryDelay: {
    summary: 'The ceiling the multiplied redelivery delay may grow to.',
    example: '60000 — never wait more than a minute.',
  },
  deadLetterAddress: {
    summary: 'Where messages go after the last failed delivery attempt. Declare the address, or the messages are dropped.',
    example: 'DLQ — one dead-letter address for the whole match.',
  },
  expiryAddress: {
    summary: 'Where expired messages go instead of being discarded. Leave unset to discard them.',
    example: 'ExpiryQueue — keep expired messages for inspection.',
  },
  expiryDelay: {
    summary:
      'A time-to-live (milliseconds) stamped on messages that arrive without one. -1 leaves such messages without an expiry.',
    example: '86400000 — a day, for messages sent without a TTL.',
  },
  minExpiryDelay: {
    summary: 'The shortest time-to-live a producer may set; a shorter one is raised to this. -1 disables the floor.',
    example: '1000 — no message expires in under a second.',
  },
  maxExpiryDelay: {
    summary: 'The longest time-to-live a producer may set; a longer one is lowered to this. -1 disables the ceiling.',
    example: '604800000 — nothing lives longer than a week.',
  },
  noExpiry: {
    summary: 'When true, messages under this match never expire, whatever their time-to-live says.',
    example: 'true — an audit address whose messages must stay.',
  },
  defaultLastValueQueue: {
    summary:
      'Whether queues auto-created under this match are last-value queues, keeping only the newest message per key.',
    example: 'true — a price feed where only the latest quote matters.',
  },
  defaultLastValueKey: {
    summary: 'The message property a last-value queue groups by, when queues are auto-created under this match.',
    example: 'symbol — one retained message per instrument.',
  },
  defaultNonDestructive: {
    summary:
      'Whether auto-created queues are non-destructive, so consuming a message does not remove it — every consumer sees the full history.',
    example: 'false — the normal consume-and-remove queue.',
  },
  defaultExclusiveQueue: {
    summary: 'Whether auto-created queues deliver to one consumer at a time, in order, instead of round-robin.',
    example: 'true — strictly ordered processing with fail-over between consumers.',
  },
  defaultGroupRebalance: {
    summary: 'Whether message groups are reassigned across consumers when a consumer joins or leaves.',
    example: 'true — spread groups again after scaling consumers.',
  },
  defaultGroupRebalancePauseDispatch: {
    summary: 'Whether delivery pauses while groups are rebalanced, so no group is handled by two consumers at once.',
    example: 'true — trade a short pause for strict group ordering.',
  },
  defaultGroupBuckets: {
    summary: 'How many buckets message groups are hashed into; -1 keeps one bucket per group ID.',
    example: '1024 — bounded memory for very many group IDs.',
  },
  defaultGroupFirstKey: {
    summary: 'A property the broker sets to true on the first message of each group, so a consumer can reset state.',
    example: 'JMSXGroupFirstForConsumer — the JMS-flavoured name.',
  },
  redistributionDelay: {
    summary:
      'In a cluster, how long (milliseconds) messages wait on a node with no consumers before being moved to a node that has some. -1 never moves them.',
    example: '0 — redistribute as soon as the last consumer leaves.',
  },
  sendToDLAOnNoRoute: {
    summary: 'When true, a message that matches no queue at all goes to the dead-letter address instead of being dropped.',
    example: 'true — never lose a message to a typo in the address.',
  },
  slowConsumerThreshold: {
    summary: 'The consumption rate below which a consumer counts as slow, in the unit chosen next. -1 disables detection.',
    example: '1 — slower than one message per unit is slow.',
  },
  slowConsumerThresholdMeasurementUnit: {
    summary: 'The unit the slow-consumer threshold is measured in.',
    example: 'MESSAGES_PER_MINUTE — a threshold of 10 means ten per minute.',
  },
  slowConsumerCheckPeriod: {
    summary: 'How often (seconds) consumer rates are checked against the threshold.',
    example: '5 — check every five seconds.',
  },
  slowConsumerPolicy: {
    summary: 'What to do with a slow consumer: NOTIFY emits a management notification Studio can alert on; KILL also disconnects it.',
    example: 'NOTIFY — see it in Studio before deciding.',
  },
  autoCreateQueues: {
    summary: 'Whether the broker creates a queue on first use, when a client sends to or subscribes on one that does not exist.',
    example: 'true — convenient in development; false to make every queue deliberate.',
  },
  autoDeleteQueues: {
    summary: 'Whether the broker removes auto-created queues once they have no consumers and no messages.',
    example: 'false — keep queues until someone deletes them.',
  },
  autoDeleteCreatedQueues: {
    summary: 'Whether the broker also removes queues that were created explicitly, not just auto-created ones, once idle.',
    example: 'false — explicit queues stay.',
  },
  autoDeleteQueuesDelay: {
    summary: 'How long (milliseconds) a queue must stay idle before auto-delete removes it.',
    example: '300000 — five idle minutes.',
  },
  autoDeleteQueuesSkipUsageCheck: {
    summary: 'When true, a queue is auto-deleted even if it was never used. Normally an unused queue is left alone.',
    example: 'false — leave never-used queues.',
  },
  autoDeleteQueuesMessageCount: {
    summary: 'The most messages a queue may still hold and be auto-deleted. 0 requires empty; -1 ignores the count.',
    example: '0 — only empty queues are removed.',
  },
  defaultRingSize: {
    summary: 'The ring size for auto-created queues: keep only the newest N messages, dropping the oldest. -1 disables.',
    example: '100 — a rolling window of the last hundred messages.',
  },
  retroactiveMessageCount: {
    summary: 'How many recent messages the broker keeps per address so a new subscriber receives them retroactively. 0 disables.',
    example: '10 — late joiners get the last ten messages.',
  },
  autoCreateAddresses: {
    summary: 'Whether the broker creates an address on first use.',
    example: 'true — addresses appear as clients need them.',
  },
  autoDeleteAddresses: {
    summary: 'Whether the broker removes auto-created addresses that no longer have queues.',
    example: 'false — keep addresses until someone deletes them.',
  },
  autoDeleteAddressesDelay: {
    summary: 'How long (milliseconds) an address must stay without queues before auto-delete removes it.',
    example: '300000 — five idle minutes.',
  },
  autoDeleteAddressesSkipUsageCheck: {
    summary: 'When true, an address is auto-deleted even if it was never used.',
    example: 'false — leave never-used addresses.',
  },
  managementBrowsePageSize: {
    summary: 'How many messages a management browse (the console, Studio) returns per page.',
    example: '200 — the broker default.',
  },
  managementMessageAttributeSizeLimit: {
    summary:
      'How many bytes of a message body or property a management browse returns before truncating. -1 removes the limit, which is what a full body view in Studio needs.',
    example: '-1 — show whole bodies in Studio.',
  },
  defaultMaxConsumers: {
    summary: 'The consumer limit for auto-created queues. -1 means unlimited.',
    example: '-1 — as many consumers as connect.',
  },
  defaultPurgeOnNoConsumers: {
    summary: 'Whether auto-created queues discard their messages whenever the last consumer disconnects.',
    example: 'false — messages wait for the next consumer.',
  },
  defaultConsumersBeforeDispatch: {
    summary: 'How many consumers must be attached before an auto-created queue starts delivering.',
    example: '2 — wait for a pair of workers.',
  },
  defaultDelayBeforeDispatch: {
    summary: 'How long (milliseconds) to wait for the consumers-before-dispatch count before delivering anyway. -1 waits forever.',
    example: '5000 — deliver after five seconds regardless.',
  },
  defaultQueueRoutingType: {
    summary: 'The routing type an auto-created queue gets: ANYCAST (point-to-point) or MULTICAST (publish-subscribe).',
    example: 'ANYCAST — one consumer per message.',
  },
  defaultAddressRoutingType: {
    summary: 'The routing type an auto-created address gets.',
    example: 'MULTICAST — every subscriber gets a copy.',
  },
  defaultConsumerWindowSize: {
    summary: 'The credit (bytes) a consumer is given up front when it does not set its own window size.',
    example: '1048576 — 1 MiB of prefetch.',
  },
  autoCreateDeadLetterResources: {
    summary: 'Whether the broker creates a per-address dead-letter queue automatically, named with the prefix and suffix below.',
    example: 'true — orders.request gets DLQ.orders.request.',
  },
  deadLetterQueuePrefix: {
    summary: 'The prefix for auto-created dead-letter queues.',
    example: 'DLQ. — the broker default.',
  },
  deadLetterQueueSuffix: {
    summary: 'The suffix for auto-created dead-letter queues.',
    example: '.dead — orders.request.dead.',
  },
  autoCreateExpiryResources: {
    summary: 'Whether the broker creates a per-address expiry queue automatically, named with the prefix and suffix below.',
    example: 'true — one expiry queue per address.',
  },
  expiryQueuePrefix: {
    summary: 'The prefix for auto-created expiry queues.',
    example: 'EXP. — the broker default.',
  },
  expiryQueueSuffix: {
    summary: 'The suffix for auto-created expiry queues.',
    example: '.expired — orders.request.expired.',
  },
  enableMetrics: {
    summary: 'Whether the broker publishes metrics for queues under this match to its metrics plugin.',
    example: 'false — keep noisy temporary queues out of the dashboards.',
  },
  enableIngressTimestamp: {
    summary: 'Whether the broker stamps each arriving message with the time it was received.',
    example: 'true — measure end-to-end latency from the broker in.',
  },
  idCacheSize: {
    summary: 'How many message IDs the broker remembers per address to detect duplicates sent with a duplicate-detection ID.',
    example: '20000 — the broker default.',
  },
  initialQueueBufferSize: {
    summary: 'The initial size of the in-memory buffer a queue starts with; a power of two.',
    example: '8192 — the broker default.',
  },
};

/** Help for a key, if the file knows it. */
export function keyHelp(jsonName: string): KeyHelp | undefined {
  return KEY_HELP[jsonName];
}
