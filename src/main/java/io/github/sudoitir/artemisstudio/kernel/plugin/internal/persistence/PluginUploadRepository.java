package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code plugin_upload} access: one row per inspected, not-yet-activated jar, keyed by sha256. */
public interface PluginUploadRepository extends JpaRepository<PluginUploadEntity, String> {}
