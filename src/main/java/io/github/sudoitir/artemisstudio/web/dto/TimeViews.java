package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** Studio's own clock, so a browser never has to trust its own (ADR-0053). */
public final class TimeViews {

    private TimeViews() {}

    /**
     * One reading of Studio's clock.
     *
     * <p>Epoch milliseconds rather than the ISO-8601 {@code Instant} every other DTO
     * carries, and deliberately so: those are timestamps <em>about</em> something and
     * are read by a human, while this is a clock reading whose only consumer is
     * arithmetic. It is the same shape as the epoch-millis the SSE envelope and the
     * {@code ping} keep-alive already send, which is the other place the server tells
     * the client what time it is.
     *
     * @param nowMs Studio's clock in milliseconds since the epoch
     */
    public record TimeView(@Schema(requiredMode = REQUIRED) long nowMs) {}
}
