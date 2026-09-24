package io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence;

import java.io.Serializable;
import java.util.UUID;
import lombok.EqualsAndHashCode;

/** {@code (cluster_id, code, subject)}: one finding, and the acceptance that may cover it. */
@EqualsAndHashCode
public class FindingKey implements Serializable {
    private UUID clusterId;
    private String code;
    private String subject;

    public FindingKey() {}

    public FindingKey(UUID clusterId, String code, String subject) {
        this.clusterId = clusterId;
        this.code = code;
        this.subject = subject;
    }
}
