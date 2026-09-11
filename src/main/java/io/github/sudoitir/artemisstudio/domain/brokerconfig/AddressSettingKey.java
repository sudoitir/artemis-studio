package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one catalogue of address-setting keys (ADR-0067 D10). Every layer that touches
 * an address setting — the XML codec, the validator, the planner, the drift comparison
 * and the form — reads this enum rather than carrying its own list, so a key is either
 * known everywhere or nowhere.
 *
 * <p>Two names per key, because the broker has two: the {@code broker.xml} element
 * ({@link #xmlName}) and the field of {@code AddressSettings} that its JSON arm
 * expects ({@link #jsonName}). They are not a mechanical transformation of each other
 * ({@code address-full-policy} is {@code addressFullMessagePolicy};
 * {@code redelivery-delay-multiplier} is {@code redeliveryMultiplier}), which is the
 * reason this table exists. Element names were taken from
 * {@code FileConfigurationParser}'s constants and JSON names from the fields the
 * broker echoed on 2.44.0 ({@code docs/broker-management-notes.md} §15 M1).
 *
 * <p>Unknown keys are refused at validation, never passed through: the broker accepts
 * a key it does not know and silently does nothing with it (M1).
 */
public enum AddressSettingKey {
    ADDRESS_FULL_MESSAGE_POLICY(
            "address-full-policy",
            "addressFullMessagePolicy",
            Type.ENUM,
            List.of("PAGE", "DROP", "FAIL", "BLOCK"),
            HazardClass.HIGH),
    MAX_SIZE_BYTES("max-size-bytes", "maxSizeBytes", Type.LONG, HazardClass.HIGH),
    MAX_SIZE_MESSAGES("max-size-messages", "maxSizeMessages", Type.LONG, HazardClass.HIGH),
    MAX_SIZE_BYTES_REJECT_THRESHOLD(
            "max-size-bytes-reject-threshold", "maxSizeBytesRejectThreshold", Type.LONG, HazardClass.MEDIUM),
    MAX_READ_PAGE_BYTES("max-read-page-bytes", "maxReadPageBytes", Type.INT, HazardClass.LOW),
    MAX_READ_PAGE_MESSAGES("max-read-page-messages", "maxReadPageMessages", Type.INT, HazardClass.LOW),
    PREFETCH_PAGE_BYTES("prefetch-page-bytes", "prefetchPageBytes", Type.INT, HazardClass.LOW),
    PREFETCH_PAGE_MESSAGES("prefetch-page-messages", "prefetchPageMessages", Type.INT, HazardClass.LOW),
    PAGE_SIZE_BYTES("page-size-bytes", "pageSizeBytes", Type.INT, HazardClass.MEDIUM),
    PAGE_MAX_CACHE_SIZE("page-max-cache-size", "pageCacheMaxSize", Type.INT, HazardClass.LOW),
    PAGE_LIMIT_BYTES("page-limit-bytes", "pageLimitBytes", Type.LONG, HazardClass.HIGH),
    PAGE_LIMIT_MESSAGES("page-limit-messages", "pageLimitMessages", Type.LONG, HazardClass.HIGH),
    PAGE_FULL_MESSAGE_POLICY(
            "page-full-policy", "pageFullMessagePolicy", Type.ENUM, List.of("DROP", "FAIL"), HazardClass.HIGH),
    MAX_DELIVERY_ATTEMPTS("max-delivery-attempts", "maxDeliveryAttempts", Type.INT, HazardClass.MEDIUM),
    MESSAGE_COUNTER_HISTORY_DAY_LIMIT(
            "message-counter-history-day-limit", "messageCounterHistoryDayLimit", Type.INT, HazardClass.LOW),
    REDELIVERY_DELAY("redelivery-delay", "redeliveryDelay", Type.LONG, HazardClass.LOW),
    REDELIVERY_DELAY_MULTIPLIER("redelivery-delay-multiplier", "redeliveryMultiplier", Type.DOUBLE, HazardClass.LOW),
    REDELIVERY_COLLISION_AVOIDANCE_FACTOR(
            "redelivery-collision-avoidance-factor",
            "redeliveryCollisionAvoidanceFactor",
            Type.DOUBLE,
            HazardClass.LOW),
    MAX_REDELIVERY_DELAY("max-redelivery-delay", "maxRedeliveryDelay", Type.LONG, HazardClass.LOW),
    DEAD_LETTER_ADDRESS("dead-letter-address", "deadLetterAddress", Type.STRING, HazardClass.MEDIUM),
    EXPIRY_ADDRESS("expiry-address", "expiryAddress", Type.STRING, HazardClass.MEDIUM),
    EXPIRY_DELAY("expiry-delay", "expiryDelay", Type.LONG, HazardClass.MEDIUM),
    MIN_EXPIRY_DELAY("min-expiry-delay", "minExpiryDelay", Type.LONG, HazardClass.MEDIUM),
    MAX_EXPIRY_DELAY("max-expiry-delay", "maxExpiryDelay", Type.LONG, HazardClass.MEDIUM),
    NO_EXPIRY("no-expiry", "noExpiry", Type.BOOLEAN, HazardClass.MEDIUM),
    DEFAULT_LAST_VALUE_QUEUE("default-last-value-queue", "defaultLastValueQueue", Type.BOOLEAN, HazardClass.LOW),
    DEFAULT_LAST_VALUE_KEY("default-last-value-key", "defaultLastValueKey", Type.STRING, HazardClass.LOW),
    DEFAULT_NON_DESTRUCTIVE("default-non-destructive", "defaultNonDestructive", Type.BOOLEAN, HazardClass.LOW),
    DEFAULT_EXCLUSIVE_QUEUE("default-exclusive-queue", "defaultExclusiveQueue", Type.BOOLEAN, HazardClass.LOW),
    DEFAULT_GROUP_REBALANCE("default-group-rebalance", "defaultGroupRebalance", Type.BOOLEAN, HazardClass.LOW),
    DEFAULT_GROUP_REBALANCE_PAUSE_DISPATCH(
            "default-group-rebalance-pause-dispatch",
            "defaultGroupRebalancePauseDispatch",
            Type.BOOLEAN,
            HazardClass.LOW),
    DEFAULT_GROUP_BUCKETS("default-group-buckets", "defaultGroupBuckets", Type.INT, HazardClass.LOW),
    DEFAULT_GROUP_FIRST_KEY("default-group-first-key", "defaultGroupFirstKey", Type.STRING, HazardClass.LOW),
    REDISTRIBUTION_DELAY("redistribution-delay", "redistributionDelay", Type.LONG, HazardClass.LOW),
    SEND_TO_DLA_ON_NO_ROUTE("send-to-dla-on-no-route", "sendToDLAOnNoRoute", Type.BOOLEAN, HazardClass.LOW),
    SLOW_CONSUMER_THRESHOLD("slow-consumer-threshold", "slowConsumerThreshold", Type.LONG, HazardClass.MEDIUM),
    SLOW_CONSUMER_THRESHOLD_MEASUREMENT_UNIT(
            "slow-consumer-threshold-measurement-unit",
            "slowConsumerThresholdMeasurementUnit",
            Type.ENUM,
            List.of("MESSAGES_PER_SECOND", "MESSAGES_PER_MINUTE", "MESSAGES_PER_HOUR", "MESSAGES_PER_DAY"),
            HazardClass.LOW),
    SLOW_CONSUMER_CHECK_PERIOD("slow-consumer-check-period", "slowConsumerCheckPeriod", Type.LONG, HazardClass.LOW),
    SLOW_CONSUMER_POLICY(
            "slow-consumer-policy", "slowConsumerPolicy", Type.ENUM, List.of("KILL", "NOTIFY"), HazardClass.MEDIUM),
    AUTO_CREATE_QUEUES("auto-create-queues", "autoCreateQueues", Type.BOOLEAN, HazardClass.LOW),
    AUTO_DELETE_QUEUES("auto-delete-queues", "autoDeleteQueues", Type.BOOLEAN, HazardClass.MEDIUM),
    AUTO_DELETE_CREATED_QUEUES(
            "auto-delete-created-queues", "autoDeleteCreatedQueues", Type.BOOLEAN, HazardClass.MEDIUM),
    AUTO_DELETE_QUEUES_DELAY("auto-delete-queues-delay", "autoDeleteQueuesDelay", Type.LONG, HazardClass.LOW),
    AUTO_DELETE_QUEUES_SKIP_USAGE_CHECK(
            "auto-delete-queues-skip-usage-check", "autoDeleteQueuesSkipUsageCheck", Type.BOOLEAN, HazardClass.MEDIUM),
    AUTO_DELETE_QUEUES_MESSAGE_COUNT(
            "auto-delete-queues-message-count", "autoDeleteQueuesMessageCount", Type.LONG, HazardClass.MEDIUM),
    DEFAULT_RING_SIZE("default-ring-size", "defaultRingSize", Type.LONG, HazardClass.MEDIUM),
    RETROACTIVE_MESSAGE_COUNT("retroactive-message-count", "retroactiveMessageCount", Type.LONG, HazardClass.LOW),
    AUTO_CREATE_ADDRESSES("auto-create-addresses", "autoCreateAddresses", Type.BOOLEAN, HazardClass.LOW),
    AUTO_DELETE_ADDRESSES("auto-delete-addresses", "autoDeleteAddresses", Type.BOOLEAN, HazardClass.MEDIUM),
    AUTO_DELETE_ADDRESSES_DELAY("auto-delete-addresses-delay", "autoDeleteAddressesDelay", Type.LONG, HazardClass.LOW),
    AUTO_DELETE_ADDRESSES_SKIP_USAGE_CHECK(
            "auto-delete-addresses-skip-usage-check",
            "autoDeleteAddressesSkipUsageCheck",
            Type.BOOLEAN,
            HazardClass.MEDIUM),
    MANAGEMENT_BROWSE_PAGE_SIZE("management-browse-page-size", "managementBrowsePageSize", Type.INT, HazardClass.LOW),
    MANAGEMENT_MESSAGE_ATTRIBUTE_SIZE_LIMIT(
            "management-message-attribute-size-limit",
            "managementMessageAttributeSizeLimit",
            Type.INT,
            HazardClass.LOW),
    DEFAULT_MAX_CONSUMERS("default-max-consumers", "defaultMaxConsumers", Type.INT, HazardClass.LOW),
    DEFAULT_PURGE_ON_NO_CONSUMERS(
            "default-purge-on-no-consumers", "defaultPurgeOnNoConsumers", Type.BOOLEAN, HazardClass.MEDIUM),
    DEFAULT_CONSUMERS_BEFORE_DISPATCH(
            "default-consumers-before-dispatch", "defaultConsumersBeforeDispatch", Type.INT, HazardClass.LOW),
    DEFAULT_DELAY_BEFORE_DISPATCH(
            "default-delay-before-dispatch", "defaultDelayBeforeDispatch", Type.LONG, HazardClass.LOW),
    DEFAULT_QUEUE_ROUTING_TYPE(
            "default-queue-routing-type",
            "defaultQueueRoutingType",
            Type.ENUM,
            List.of("ANYCAST", "MULTICAST"),
            HazardClass.LOW),
    DEFAULT_ADDRESS_ROUTING_TYPE(
            "default-address-routing-type",
            "defaultAddressRoutingType",
            Type.ENUM,
            List.of("ANYCAST", "MULTICAST"),
            HazardClass.LOW),
    DEFAULT_CONSUMER_WINDOW_SIZE(
            "default-consumer-window-size", "defaultConsumerWindowSize", Type.INT, HazardClass.LOW),
    AUTO_CREATE_DEAD_LETTER_RESOURCES(
            "auto-create-dead-letter-resources", "autoCreateDeadLetterResources", Type.BOOLEAN, HazardClass.LOW),
    DEAD_LETTER_QUEUE_PREFIX("dead-letter-queue-prefix", "deadLetterQueuePrefix", Type.STRING, HazardClass.LOW),
    DEAD_LETTER_QUEUE_SUFFIX("dead-letter-queue-suffix", "deadLetterQueueSuffix", Type.STRING, HazardClass.LOW),
    AUTO_CREATE_EXPIRY_RESOURCES(
            "auto-create-expiry-resources", "autoCreateExpiryResources", Type.BOOLEAN, HazardClass.LOW),
    EXPIRY_QUEUE_PREFIX("expiry-queue-prefix", "expiryQueuePrefix", Type.STRING, HazardClass.LOW),
    EXPIRY_QUEUE_SUFFIX("expiry-queue-suffix", "expiryQueueSuffix", Type.STRING, HazardClass.LOW),
    ENABLE_METRICS("enable-metrics", "enableMetrics", Type.BOOLEAN, HazardClass.LOW),
    ENABLE_INGRESS_TIMESTAMP("enable-ingress-timestamp", "enableIngressTimestamp", Type.BOOLEAN, HazardClass.LOW),
    ID_CACHE_SIZE("id-cache-size", "idCacheSize", Type.INT, HazardClass.LOW),
    INITIAL_QUEUE_BUFFER_SIZE("initial-queue-buffer-size", "initialQueueBufferSize", Type.INT, HazardClass.LOW),
    /**
     * The three {@code config-delete-*} keys are in the catalogue so import can name
     * them, and {@link #applicable} is false so validation refuses them: they govern
     * what a <em>configuration reload</em> removes, which is a file-owned decision no
     * runtime write should make.
     */
    CONFIG_DELETE_QUEUES(
            "config-delete-queues", "configDeleteQueues", Type.ENUM, List.of("OFF", "FORCE"), HazardClass.HIGH, false),
    CONFIG_DELETE_ADDRESSES(
            "config-delete-addresses",
            "configDeleteAddresses",
            Type.ENUM,
            List.of("OFF", "FORCE"),
            HazardClass.HIGH,
            false),
    CONFIG_DELETE_DIVERTS(
            "config-delete-diverts",
            "configDeleteDiverts",
            Type.ENUM,
            List.of("OFF", "FORCE"),
            HazardClass.HIGH,
            false);

    /** The scalar type a key's value must have. */
    public enum Type {
        BOOLEAN,
        INT,
        LONG,
        DOUBLE,
        STRING,
        ENUM
    }

    private static final Map<String, AddressSettingKey> BY_XML = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(k -> k.xmlName, Function.identity()));
    private static final Map<String, AddressSettingKey> BY_JSON = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(k -> k.jsonName, Function.identity()));

    private final String xmlName;
    private final String jsonName;
    private final Type type;
    private final List<String> allowedValues;
    private final HazardClass hazardClass;
    private final boolean applicable;

    AddressSettingKey(String xmlName, String jsonName, Type type, HazardClass hazardClass) {
        this(xmlName, jsonName, type, List.of(), hazardClass, true);
    }

    AddressSettingKey(String xmlName, String jsonName, Type type, List<String> allowedValues, HazardClass hazardClass) {
        this(xmlName, jsonName, type, allowedValues, hazardClass, true);
    }

    AddressSettingKey(
            String xmlName,
            String jsonName,
            Type type,
            List<String> allowedValues,
            HazardClass hazardClass,
            boolean applicable) {
        this.xmlName = xmlName;
        this.jsonName = jsonName;
        this.type = type;
        this.allowedValues = allowedValues;
        this.hazardClass = hazardClass;
        this.applicable = applicable;
    }

    public String xmlName() {
        return xmlName;
    }

    public String jsonName() {
        return jsonName;
    }

    public Type type() {
        return type;
    }

    /** Allowed values for an {@link Type#ENUM} key; empty otherwise. */
    public List<String> allowedValues() {
        return allowedValues;
    }

    /** The hazard class a change to this key carries before the planner's own checks. */
    public HazardClass hazardClass() {
        return hazardClass;
    }

    /** Whether a runtime write may carry this key at all. */
    public boolean applicable() {
        return applicable;
    }

    public static Optional<AddressSettingKey> byXmlName(String name) {
        return Optional.ofNullable(name == null ? null : BY_XML.get(name.toLowerCase(Locale.ROOT)));
    }

    public static Optional<AddressSettingKey> byJsonName(String name) {
        return Optional.ofNullable(name == null ? null : BY_JSON.get(name));
    }
}
