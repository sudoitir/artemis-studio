package io.github.sudoitir.artemisstudio.kernel.inbox;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;

/** The in-app inbox. Module descriptor (ADR-0070). */
public final class InboxModule {

    public static final String ID = "inbox";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id(ID)
            .title("Inbox")
            .kind(FeatureDescriptor.Kind.KERNEL)
            .required(true)
            .apiPrefix("/api/v1/inbox")
            .settingKey(InboxSettings.READ_RETENTION)
            .build();

    private InboxModule() {}
}
