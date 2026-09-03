package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code studio_setting} (changeset 009). One row per operator-tunable key;
 * the value is a JSON string (scalar or object) so a key can gain structure
 * without a migration. Low churn — plain JPA, unlike the disposable
 * {@code queue_snapshot} cache.
 */
@Entity
@Table(name = "studio_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudioSettingEntity {

    @Id
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "value", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StudioSettingEntity(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}
