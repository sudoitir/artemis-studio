package io.github.sudoitir.artemisstudio.kernel.plugin;

/** One permission string a module checks (ADR-0038), with its role-editor label. */
public record PermissionDef(String action, String label) {}
