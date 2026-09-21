package io.github.sudoitir.artemisstudio.platform.broker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.core.client.impl.ClientMessageImpl;

/**
 * Builds the message a relay sends from the one it received (transfer design D3): the same message
 * to its consumers, minus what only meant something on the broker it came from, plus where it came
 * from.
 *
 * <ul>
 *   <li><b>Copied:</b> the body bytes and type verbatim (Text, Bytes, Map, Stream and Object alike),
 *       durability, priority, absolute expiration, timestamp, user id, and every property outside
 *       the dropped set, which keeps group, correlation, reply-to and last-value keys.
 *   <li><b>Dropped:</b> the broker's routing and bookkeeping headers ({@link #DROPPED}).
 *   <li><b>Added:</b> a duplicate-detection id {@code studio:<run>:<source message id>}, so a batch
 *       relayed twice is dropped by the target, and the {@code _studio_*} provenance properties.
 * </ul>
 *
 * <p>A large message's body is saved to an owner-only file under {@link #spoolRoot()}, after checking
 * the disk has room, and streamed from there. It is not piped from the source straight to the
 * target: that would tie two sessions' flow control together inside one transaction.
 */
public final class OutboundMessages {

    public static final SimpleString RUN = SimpleString.of("_studio_transfer_run");
    public static final SimpleString ORIG_CLUSTER = SimpleString.of("_studio_orig_cluster");
    public static final SimpleString ORIG_NODE = SimpleString.of("_studio_orig_node");
    public static final SimpleString ORIG_QUEUE = SimpleString.of("_studio_orig_queue");
    public static final SimpleString ORIG_MESSAGE_ID = SimpleString.of("_studio_orig_message_id");

    /** Headers that describe the message's life on the source broker and must not travel. */
    static final Set<SimpleString> DROPPED = Set.of(
            Message.HDR_ORIGINAL_ADDRESS,
            Message.HDR_ORIGINAL_QUEUE,
            Message.HDR_ORIG_MESSAGE_ID,
            Message.HDR_ORIG_ROUTING_TYPE,
            Message.HDR_ROUTE_TO_IDS,
            Message.HDR_ROUTE_TO_ACK_IDS,
            Message.HDR_SCALEDOWN_TO_IDS,
            Message.HDR_BRIDGE_DUPLICATE_ID,
            Message.HDR_ROUTING_TYPE,
            Message.HDR_DUPLICATE_DETECTION_ID,
            Message.HDR_ACTUAL_EXPIRY_TIME,
            // Describe the source's stored copy; the target's client sets its own when it streams.
            Message.HDR_LARGE_BODY_SIZE,
            Message.HDR_LARGE_COMPRESSED);

    private OutboundMessages() {}

    /**
     * Where a message came from. {@code staged} says the relayed message is a staging copy made by a
     * broker-side move, whose id on the source queue is in {@code _AMQ_ORIG_MESSAGE_ID}; otherwise
     * (a copy browsing the source) its own id is the source id.
     */
    public record Provenance(UUID runId, UUID cluster, String node, String queue, boolean staged) {}

    /**
     * The message to send, and the spool file behind a large one. Close it once the target has
     * committed, or given up: that closes the body stream and deletes the file.
     */
    public record Outbound(ClientMessage message, Path spool, InputStream body) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (body != null) {
                    body.close();
                }
                if (spool != null) {
                    Files.deleteIfExists(spool);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not delete the large-message spool file " + spool, e);
            }
        }
    }

    /** {@code ${java.io.tmpdir}/studio-transfer}: one directory per run holds its large-message spool files. */
    public static Path spoolRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "studio-transfer");
    }

    public static Outbound from(ClientMessage source, Provenance p) throws ActiveMQException, IOException {
        ClientMessageImpl target = new ClientMessageImpl(
                source.getType(),
                source.isDurable(),
                source.getExpiration(),
                source.getTimestamp(),
                source.getPriority(),
                1024);
        target.setUserID(source.getUserID());
        for (SimpleString name : source.getPropertyNames()) {
            if (!DROPPED.contains(name)) {
                target.putObjectProperty(name, source.getObjectProperty(name));
            }
        }
        long sourceId = source.getMessageID();
        Long originalId = p.staged() ? source.getLongProperty(Message.HDR_ORIG_MESSAGE_ID) : null;
        target.putStringProperty(Message.HDR_DUPLICATE_DETECTION_ID, "studio:" + p.runId() + ":" + sourceId);
        target.putStringProperty(RUN, p.runId().toString());
        target.putStringProperty(ORIG_CLUSTER, p.cluster().toString());
        target.putStringProperty(ORIG_NODE, p.node());
        target.putStringProperty(ORIG_QUEUE, p.queue());
        target.putLongProperty(ORIG_MESSAGE_ID, originalId != null ? originalId : sourceId);

        if (!source.isLargeMessage()) {
            ActiveMQBuffer body = source.getReadOnlyBodyBuffer();
            byte[] bytes = new byte[body.readableBytes()];
            body.readBytes(bytes);
            target.getBodyBuffer().writeBytes(bytes);
            return new Outbound(target, null, null);
        }
        Path spool = spool(p.runId(), largeSize(source));
        try {
            try (OutputStream out = Files.newOutputStream(spool)) {
                source.saveToOutputStream(out);
            }
            InputStream in = Files.newInputStream(spool);
            target.setBodyInputStream(in);
            return new Outbound(target, spool, in);
        } catch (ActiveMQException | IOException | RuntimeException e) {
            Files.deleteIfExists(spool);
            throw e;
        }
    }

    private static long largeSize(ClientMessage source) {
        Long size = source.getLongProperty(Message.HDR_LARGE_BODY_SIZE);
        return size != null ? size : source.getBodySize();
    }

    /** A new owner-only file in the run's spool directory, once the disk is known to have room for it. */
    private static Path spool(UUID runId, long size) throws IOException {
        Path dir = spoolRoot().resolve(runId.toString());
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectories(
                    dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(dir);
        }
        long usable = Files.getFileStore(dir).getUsableSpace();
        if (usable < size) {
            throw new IOException("Relaying a large message of " + size + " bytes needs that much free disk under "
                    + dir + ", and only " + usable + " bytes are free. Free disk space for Studio, then resume.");
        }
        FileAttribute<?>[] attrs = posix
                ? new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                }
                : new FileAttribute<?>[0];
        return Files.createTempFile(dir, "message-", ".body", attrs);
    }

    /** Delete every spool file. Nothing still needs one once Studio has restarted: a resumed run receives again. */
    public static void sweep() {
        Path root = spoolRoot();
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // One undeletable file must not stop the rest being swept.
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not sweep the large-message spool " + root, e);
        }
    }
}
