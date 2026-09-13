package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.List;
import lombok.Builder;
import lombok.Singular;

/**
 * The static facts one module declares about itself (ADR-0070). Behaviour is
 * contributed as beans of kernel SPI types; this record is only what an
 * installation must know about a module even when that module is disabled.
 *
 * @param id stable kebab-case identifier, equal to the frontend feature id
 * @param contract the contract version the module was built against; defaults to {@link Contract#VERSION}
 * @param required a required module cannot be disabled
 * @param requires ids of modules that must be enabled for this one to be
 * @param apiPrefixes path patterns under {@code /api/v1} this module serves, used to explain a disabled feature
 */
@Builder
public record FeatureDescriptor(
        String id,
        int contract,
        String title,
        Kind kind,
        boolean required,
        @Singular("require") List<String> requires,
        @Singular List<PermissionDef> permissions,
        @Singular List<SettingDef> settings,
        @Singular List<TopicDef> streamTopics,
        @Singular List<McpToolDef> mcpTools,
        @Singular List<String> apiPrefixes) {

    public enum Kind {
        KERNEL,
        PLATFORM,
        FEATURE,
        IDENTITY_PROVIDER
    }

    public FeatureDescriptor {
        if (id == null || !id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Feature id must be kebab-case: " + id);
        }
        if (contract == 0) {
            contract = Contract.VERSION;
        }
        if (kind == null) {
            throw new IllegalArgumentException("Feature " + id + " declares no kind");
        }
        requires = List.copyOf(requires == null ? List.of() : requires);
        permissions = List.copyOf(permissions == null ? List.of() : permissions);
        settings = List.copyOf(settings == null ? List.of() : settings);
        streamTopics = List.copyOf(streamTopics == null ? List.of() : streamTopics);
        mcpTools = List.copyOf(mcpTools == null ? List.of() : mcpTools);
        apiPrefixes = List.copyOf(apiPrefixes == null ? List.of() : apiPrefixes);
    }
}
