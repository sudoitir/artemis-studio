package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code plugin_install} access: one row per installed plugin id. */
public interface PluginInstallRepository extends JpaRepository<PluginInstallEntity, String> {

    /** For the connection budget (design.md §2): how many plugins are currently serving traffic. */
    long countByStatus(String status);

    /** For the single-provider rule: the approval providers whose status is one of {@code statuses}. */
    List<PluginInstallEntity> findByApprovalProviderTrueAndStatusIn(Collection<String> statuses);
}
