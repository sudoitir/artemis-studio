package io.github.sudoitir.artemisstudio.feature.plugins.messaging.internal;

/** A tap the broker's configuration makes unsafe or useless; the message says why and what to change. */
class TapRefusedException extends RuntimeException {
    TapRefusedException(String message) {
        super(message);
    }
}
