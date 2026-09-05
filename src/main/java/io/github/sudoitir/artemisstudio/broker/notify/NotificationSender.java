package io.github.sudoitir.artemisstudio.broker.notify;

import java.time.Duration;

/**
 * One channel kind's delivery mechanism (ADR-0036). {@link #send} never
 * throws for an ordinary delivery failure — it reports the outcome so
 * {@code AlertDispatcher} can decide retry vs. permanent failure without a
 * try/catch per sender.
 */
public interface NotificationSender {

    String kind();

    Result send(long deliveryId, String channelConfigJson, String secret, String payloadJson);

    /**
     * @param success delivered
     * @param permanent true when retrying would never help (e.g. a revoked Slack
     *     webhook) — the dispatcher marks the delivery DEAD immediately
     * @param retryAfter a receiver-requested minimum delay before the next retry
     *     (e.g. HTTP 429 {@code Retry-After}), or null to use the default backoff
     */
    record Result(boolean success, boolean permanent, String error, Duration retryAfter) {
        public static Result ok() {
            return new Result(true, false, null, null);
        }

        public static Result retryable(String error) {
            return new Result(false, false, error, null);
        }

        public static Result retryable(String error, Duration retryAfter) {
            return new Result(false, false, error, retryAfter);
        }

        public static Result permanent(String error) {
            return new Result(false, true, error, null);
        }
    }
}
