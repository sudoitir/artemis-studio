package io.github.sudoitir.artemisstudio.broker.capture;

import lombok.Getter;

/**
 * Capture cannot be installed on this node, and the next pass will not fix it
 * (ADR-0062 D6). Distinct from a connection failure, which is transient and retried:
 * this is a statement about how the broker is configured, and it carries the
 * configuration that would change the answer.
 *
 * <p>It exists because the alternative outcomes are both worse than a refusal. An
 * install that silently captures nothing is indistinguishable from a quiet queue, and
 * an install that silently leaves a second copy of production payload unguarded is a
 * security incident Studio caused.
 */
@Getter
public class CaptureRefusedException extends RuntimeException {

    /** The {@code broker.xml} that would make this install succeed, or null when none would. */
    private final String brokerXml;

    public CaptureRefusedException(String message, String brokerXml) {
        super(message);
        this.brokerXml = brokerXml;
    }
}
