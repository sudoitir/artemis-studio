package io.github.sudoitir.artemisstudio.platform.governance;

/** What a sensitive value is, and what happens to it unless a rule says otherwise (ADR-0075 D1). */
public enum DataClass {
    CREDENTIAL(Action.DROP, "credential"),
    PAN(Action.PARTIAL, "payment card number"),
    IBAN(Action.PARTIAL, "IBAN"),
    EMAIL(Action.REDACT, "email"),
    PHONE(Action.REDACT, "phone number"),
    NATIONAL_ID(Action.REDACT, "national identifier"),
    PERSONAL(Action.REDACT, "personal data");

    private final Action defaultAction;
    private final String label;

    DataClass(Action defaultAction, String label) {
        this.defaultAction = defaultAction;
        this.label = label;
    }

    public Action defaultAction() {
        return defaultAction;
    }

    public String label() {
        return label;
    }

    /** Credentials are destroyed, never sealed and never shown in clear. */
    public boolean sealable() {
        return this != CREDENTIAL;
    }
}
