package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;

/** SQL over messages, the message index and capture. Module descriptor (ADR-0070). */
public final class SqlModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("sql")
            .title("SQL console")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("queues")
            .require("routing")
            .permission(new PermissionDef(SqlPermissions.CAPTURE_WRITE, "Turn message capture on and off"))
            .apiPrefix("/api/v1/clusters/{clusterId}/sql")
            .build();

    private SqlModule() {}
}
