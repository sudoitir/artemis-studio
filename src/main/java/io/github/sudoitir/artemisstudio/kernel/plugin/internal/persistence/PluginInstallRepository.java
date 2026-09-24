package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code plugin_install} access: one row per installed plugin id. */
public interface PluginInstallRepository extends JpaRepository<PluginInstallEntity, String> {

    /** For the connection budget (design.md §2): how many plugins are currently serving traffic. */
    long countByStatus(String status);
}
