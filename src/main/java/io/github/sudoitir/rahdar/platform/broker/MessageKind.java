package io.github.sudoitir.rahdar.platform.broker;

/** What a sent message's body is: {@link #TEXT} sends the body as text, {@link #BYTES} base64-decodes it into bytes. */
public enum MessageKind {
    TEXT,
    BYTES
}
