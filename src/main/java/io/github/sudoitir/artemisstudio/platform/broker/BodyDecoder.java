package io.github.sudoitir.artemisstudio.platform.broker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads a bytes message's body as text when it is text (ADR-0148). Decided once, where Studio reads the
 * body, so every view of the message agrees: the message view, the index, governance, SQL, request-reply.
 *
 * <p>Text means strict UTF-8 with no control characters but tab, line feed and carriage return. A gzip or
 * zlib body is decompressed, one level and at most {@link #MAX_DECOMPRESSED_BYTES}, and is text when what
 * comes out is. Anything else is binary, and so is anything that fails: decoding never fails a read.
 */
public final class BodyDecoder {

    /**
     * The most a compressed body may expand to; beyond it the body stays binary. A browse page holds up to
     * 200 messages, so this bounds a page of decompression bombs to 400 MiB rather than gigabytes, and it
     * matches the size above which the message view stops formatting anyway.
     */
    public static final int MAX_DECOMPRESSED_BYTES = 2 * 1024 * 1024;

    public enum Compression {
        NONE,
        GZIP,
        DEFLATE;

        /** As the API names it: {@code gzip} or {@code deflate}, and null for none. */
        public String apiName() {
            return this == NONE ? null : name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code text} is null when the body is binary. */
    public record Decoded(String text, Compression compression) {

        static final Decoded BINARY = new Decoded(null, Compression.NONE);

        public boolean isBinary() {
            return text == null;
        }
    }

    private BodyDecoder() {}

    public static Decoded decode(byte[] raw) {
        try {
            Compression compression = compressionOf(raw);
            if (compression != Compression.NONE) {
                String inner = inflate(raw, compression);
                if (inner != null) {
                    return new Decoded(inner, compression);
                }
            }
            String text = text(raw);
            return text != null ? new Decoded(text, Compression.NONE) : Decoded.BINARY;
        } catch (RuntimeException _) {
            return Decoded.BINARY;
        }
    }

    /**
     * Like {@link #decode}, for leading bytes cut at a size limit: the cut may split the last UTF-8 sequence,
     * so up to three trailing bytes are dropped before the body is called binary.
     */
    public static Decoded decodePrefix(byte[] raw) {
        for (int drop = 0; drop <= 3 && drop <= raw.length; drop++) {
            Decoded decoded = decode(java.util.Arrays.copyOf(raw, raw.length - drop));
            if (!decoded.isBinary()) {
                return decoded;
            }
        }
        return Decoded.BINARY;
    }

    private static Compression compressionOf(byte[] raw) {
        if (raw.length < 2) {
            return Compression.NONE;
        }
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        if (b0 == 0x1F && b1 == 0x8B) {
            return Compression.GZIP;
        }
        // RFC 1950: CM 8 (deflate) with a 32 KiB window, and the header check bits.
        if (b0 == 0x78 && (b0 * 256 + b1) % 31 == 0) {
            return Compression.DEFLATE;
        }
        return Compression.NONE;
    }

    /** The decompressed body as text, or null when it is corrupt, too large or not text. */
    private static String inflate(byte[] raw, Compression compression) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = compression == Compression.GZIP
                ? new GZIPInputStream(new ByteArrayInputStream(raw))
                : new InflaterInputStream(new ByteArrayInputStream(raw))) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_DECOMPRESSED_BYTES) {
                    return null;
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException _) {
            return null;
        }
        return text(out.toByteArray());
    }

    /** Strict UTF-8 without control characters (C0, DEL, C1), or null. */
    private static String text(byte[] raw) {
        String s;
        try {
            s = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException _) {
            return null;
        }
        boolean control = s.chars()
                .anyMatch(c -> Character.getType(c) == Character.CONTROL && c != '\t' && c != '\n' && c != '\r');
        return control ? null : s;
    }
}
