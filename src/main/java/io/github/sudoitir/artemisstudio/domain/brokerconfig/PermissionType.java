package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import java.util.Locale;
import java.util.Optional;

/**
 * The twelve permission types of a security setting, <em>in the order the broker's
 * 13-String {@code addSecuritySettings} arm takes them</em> after the match. That
 * order was confirmed by writing and reading back through {@code getRolesAsJSON}
 * ({@code CaptureTap}), and the enum's ordinal is what the operations layer relies on.
 * The XML {@code type} attribute and the JSON field of {@code getRolesAsJSON} both use
 * {@link #xmlName}.
 *
 * <p>{@code view} and {@code edit} are {@linkplain #echoed() not echoed}: on 2.44.0 the
 * broker accepts their role lists through that arm but {@code getRolesAsJSON} reports
 * them as {@code false} for every role ({@code docs/broker-management-notes.md} §15
 * M7). They are still sent; they are left out of every comparison, and the screen says
 * so, because a key nobody can read back must not produce drift nobody can close.
 */
public enum PermissionType {
    SEND("send"),
    CONSUME("consume"),
    CREATE_DURABLE_QUEUE("createDurableQueue"),
    DELETE_DURABLE_QUEUE("deleteDurableQueue"),
    CREATE_NON_DURABLE_QUEUE("createNonDurableQueue"),
    DELETE_NON_DURABLE_QUEUE("deleteNonDurableQueue"),
    MANAGE("manage"),
    BROWSE("browse"),
    CREATE_ADDRESS("createAddress"),
    DELETE_ADDRESS("deleteAddress"),
    VIEW("view", false),
    EDIT("edit", false);

    private final String xmlName;
    private final boolean echoed;

    PermissionType(String xmlName) {
        this(xmlName, true);
    }

    PermissionType(String xmlName, boolean echoed) {
        this.xmlName = xmlName;
        this.echoed = echoed;
    }

    public String xmlName() {
        return xmlName;
    }

    /** Whether {@code getRolesAsJSON} reports this type back, so it can be verified and drift-checked. */
    public boolean echoed() {
        return echoed;
    }

    public static Optional<PermissionType> byXmlName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (PermissionType t : values()) {
            if (t.xmlName.equalsIgnoreCase(name) || t.name().equalsIgnoreCase(name.toUpperCase(Locale.ROOT))) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
