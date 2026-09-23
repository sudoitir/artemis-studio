package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code plugin_install} access: one row per installed plugin id. */
public interface PluginInstallRepository extends JpaRepository<PluginInstallEntity, String> {}
