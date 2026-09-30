package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.artemisstudio.platform.broker.BodyDecoder.Decoded;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class BodyDecoderTest {

    private static final String JSON = "{\"orderId\":42,\"status\":\"FAILED\",\"note\":\"café ✓\"}\n";

    @Test
    void utf8JsonIsText() {
        Decoded d = BodyDecoder.decode(utf8(JSON));
        assertThat(d.text()).isEqualTo(JSON);
        assertThat(d.compression()).isEqualTo(Compression.NONE);
    }

    @Test
    void emptyIsText() {
        assertThat(BodyDecoder.decode(new byte[0]).text()).isEmpty();
    }

    @Test
    void aByteOrderMarkRoundTrips() {
        byte[] raw = utf8("﻿{\"a\":1}");
        assertThat(BodyDecoder.decode(raw).text().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(raw);
    }

    @Test
    void malformedUtf8IsBinary() {
        assertThat(BodyDecoder.decode(new byte[] {'{', (byte) 0xC3, '}'}).isBinary())
                .isTrue();
    }

    @Test
    void overlongEncodingIsBinary() {
        // 0xC0 0xAF is an overlong '/', which a lenient decoder would accept.
        assertThat(BodyDecoder.decode(new byte[] {(byte) 0xC0, (byte) 0xAF}).isBinary())
                .isTrue();
    }

    @Test
    void encodedSurrogateIsBinary() {
        // U+D800 encoded directly: well-formed bytes, but not valid UTF-8.
        assertThat(BodyDecoder.decode(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80})
                        .isBinary())
                .isTrue();
    }

    @Test
    void controlCharactersAreBinary() {
        assertThat(BodyDecoder.decode(new byte[] {'a', 0, 'b'}).isBinary()).isTrue();
        assertThat(BodyDecoder.decode(new byte[] {'a', 0x1B, 'b'}).isBinary()).isTrue();
        assertThat(BodyDecoder.decode(new byte[] {'a', 0x7F, 'b'}).isBinary()).isTrue();
        assertThat(BodyDecoder.decode(utf8("a\u0085b")).isBinary()).isTrue();
        assertThat(BodyDecoder.decode(utf8("a\tb\r\nc")).text()).isEqualTo("a\tb\r\nc");
    }

    @Test
    void gzipJsonIsDecompressed() throws IOException {
        Decoded d = BodyDecoder.decode(gzip(utf8(JSON)));
        assertThat(d.text()).isEqualTo(JSON);
        assertThat(d.compression()).isEqualTo(Compression.GZIP);
    }

    @Test
    void deflateJsonIsDecompressed() throws IOException {
        Decoded d = BodyDecoder.decode(deflate(utf8(JSON)));
        assertThat(d.text()).isEqualTo(JSON);
        assertThat(d.compression()).isEqualTo(Compression.DEFLATE);
    }

    @Test
    void gzipOfBinaryIsBinary() throws IOException {
        assertThat(BodyDecoder.decode(gzip(new byte[] {1, 2, 3, 0})).isBinary()).isTrue();
    }

    @Test
    void corruptGzipIsBinary() throws IOException {
        byte[] whole = gzip(utf8(JSON.repeat(50)));
        byte[] cut = java.util.Arrays.copyOf(whole, whole.length / 2);
        assertThat(BodyDecoder.decode(cut).isBinary()).isTrue();
        assertThat(BodyDecoder.decode(new byte[] {0x1f, (byte) 0x8b, 9, 9, 9}).isBinary())
                .isTrue();
    }

    @Test
    void aDecompressionBombStopsAtTheCeiling() throws IOException {
        // Past the ceiling in spaces: valid text if it were read whole, so only the ceiling stops it.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream gz = new GZIPOutputStream(out)) {
            byte[] block = new byte[1 << 20];
            java.util.Arrays.fill(block, (byte) ' ');
            for (int i = 0; i <= BodyDecoder.MAX_DECOMPRESSED_BYTES >> 20; i++) {
                gz.write(block);
            }
        }
        assertThat(BodyDecoder.decode(out.toByteArray()).isBinary()).isTrue();
    }

    @Test
    void onlyOneLevelIsUnwrapped() throws IOException {
        // The inner gzip header is binary, so the outer layer does not decompress to text.
        assertThat(BodyDecoder.decode(gzip(gzip(utf8(JSON)))).isBinary()).isTrue();
    }

    @Test
    void textThatLooksLikeAZlibHeaderStaysText() {
        // 'x' then '^' is 0x78 0x5E, a valid zlib header; the body is still plain text.
        assertThat(BodyDecoder.decode(utf8("x^ marks the spot")).text()).isEqualTo("x^ marks the spot");
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream z = new DeflaterOutputStream(out)) {
            z.write(raw);
        }
        return out.toByteArray();
    }
}
