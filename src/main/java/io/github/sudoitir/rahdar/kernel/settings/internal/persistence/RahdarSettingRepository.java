package io.github.sudoitir.rahdar.kernel.settings.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** {@code rahdar_setting} access (ADR-0011). Key is the string setting name. */
public interface RahdarSettingRepository extends JpaRepository<RahdarSettingEntity, String> {

    /** Writes the row only when no replica has yet; returns 1 when this call wrote it. */
    @Modifying
    @Transactional
    @Query(
            nativeQuery = true,
            value = "INSERT INTO rahdar_setting (key, value) VALUES (:key, CAST(:value AS jsonb))"
                    + " ON CONFLICT (key) DO NOTHING")
    int insertIfAbsent(@Param("key") String key, @Param("value") String value);
}
