package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Maps {@code plugin_upload} (changeset kernel-plugin-0005): an inspected, inert upload awaiting
 * activation. {@link io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost}
 * only ever deletes a row here — the one for the sha it just successfully activated (task 6.8,
 * design.md §5) — so only the columns that identify a row are mapped; {@code descriptor} and
 * {@code report} belong to the upload/review endpoint (task 7.4), out of this slice.
 */
@Entity
@Table(name = "plugin_upload")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PluginUploadEntity {

    @Id
    @Column(name = "sha256", nullable = false, updatable = false)
    private String sha256;

    @Column(name = "plugin_id", nullable = false, updatable = false)
    private String pluginId;
}
