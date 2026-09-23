package io.github.sudoitir.artemisstudio.kernel.plugin.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A hand-rolled, STORED-only zip writer, for the one shape {@link PluginJarBuilder} cannot
 * produce: a jar with a duplicate entry name. {@link java.util.zip.ZipOutputStream} refuses to
 * write one (it throws {@code duplicate entry}), so a fixture that needs one has to write the
 * local and central directory records itself.
 */
public final class RawZip {

    public record Entry(String name, byte[] content) {}

    /**
     * A zip whose central directory lists only {@code centralNames} of the local entries — the
     * zip-confusion shape, where a sequential reader and {@link java.util.zip.ZipFile} see
     * different files.
     */
    public static byte[] buildWithCentralSubset(List<Entry> entries, java.util.Set<String> centralNames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] localOffsets = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            localOffsets[i] = out.size();
            writeLocal(out, entries.get(i));
        }
        int centralStart = out.size();
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (centralNames.contains(entries.get(i).name())) {
                writeCentral(out, entries.get(i), localOffsets[i]);
                count++;
            }
        }
        writeEnd(out, count, out.size() - centralStart, centralStart);
        return out.toByteArray();
    }

    public static byte[] build(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] localOffsets = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            localOffsets[i] = out.size();
            writeLocal(out, entries.get(i));
        }
        int centralStart = out.size();
        for (int i = 0; i < entries.size(); i++) {
            writeCentral(out, entries.get(i), localOffsets[i]);
        }
        int centralSize = out.size() - centralStart;
        writeEnd(out, entries.size(), centralSize, centralStart);
        return out.toByteArray();
    }

    private static void writeLocal(ByteArrayOutputStream out, Entry e) {
        byte[] name = e.name().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(e.content());
        writeInt(out, 0x04034b50);
        writeShort(out, 20);
        writeShort(out, 0);
        writeShort(out, 0); // method 0 = stored
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, (int) crc.getValue());
        writeInt(out, e.content().length);
        writeInt(out, e.content().length);
        writeShort(out, name.length);
        writeShort(out, 0);
        out.writeBytes(name);
        out.writeBytes(e.content());
    }

    private static void writeCentral(ByteArrayOutputStream out, Entry e, int localOffset) {
        byte[] name = e.name().getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(e.content());
        writeInt(out, 0x02014b50);
        writeShort(out, 20);
        writeShort(out, 20);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, (int) crc.getValue());
        writeInt(out, e.content().length);
        writeInt(out, e.content().length);
        writeShort(out, name.length);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, 0);
        writeInt(out, localOffset);
        out.writeBytes(name);
    }

    private static void writeEnd(ByteArrayOutputStream out, int count, int centralSize, int centralStart) {
        writeInt(out, 0x06054b50);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, count);
        writeShort(out, count);
        writeInt(out, centralSize);
        writeInt(out, centralStart);
        writeShort(out, 0);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private RawZip() {}
}
