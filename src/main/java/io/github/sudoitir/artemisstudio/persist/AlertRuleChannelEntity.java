package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Maps {@code alert_rule_channel} (changeset 013): which channels a rule routes firings to. */
@Entity
@Table(name = "alert_rule_channel")
@IdClass(AlertRuleChannelEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertRuleChannelEntity {

    @Id
    @Column(name = "rule_id", updatable = false)
    private UUID ruleId;

    @Id
    @Column(name = "channel_id", updatable = false)
    private UUID channelId;

    public AlertRuleChannelEntity(UUID ruleId, UUID channelId) {
        this.ruleId = ruleId;
        this.channelId = channelId;
    }

    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID ruleId;
        private UUID channelId;

        public Key() {}

        public Key(UUID ruleId, UUID channelId) {
            this.ruleId = ruleId;
            this.channelId = channelId;
        }
    }
}
