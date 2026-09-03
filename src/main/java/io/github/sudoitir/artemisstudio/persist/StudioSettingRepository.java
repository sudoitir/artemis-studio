package io.github.sudoitir.artemisstudio.persist;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code studio_setting} access (ADR-0011). Key is the string setting name. */
public interface StudioSettingRepository extends JpaRepository<StudioSettingEntity, String> {}
