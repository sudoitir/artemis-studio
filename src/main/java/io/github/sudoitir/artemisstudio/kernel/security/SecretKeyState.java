package io.github.sudoitir.artemisstudio.kernel.security;

/** The one KEK version every replica wraps new secrets with (stored, so restart order does not matter). */
public interface SecretKeyState {

    /** The stored current version. When none is stored yet, {@code initial} is stored for {@code provider} first. */
    int currentOrInit(int initial, String provider);
}
