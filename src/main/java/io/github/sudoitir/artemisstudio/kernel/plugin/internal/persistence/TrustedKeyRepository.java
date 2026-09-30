package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code plugin_trusted_key} access, keyed by fingerprint. */
public interface TrustedKeyRepository extends JpaRepository<TrustedKeyEntity, String> {

    List<TrustedKeyEntity> findAllByOrderByAddedAtAsc();

    long deleteByFingerprint(String fingerprint);
}
