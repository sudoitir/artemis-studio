package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * One place that keeps {@link SecretVault} blobs, for a key rotation to sweep (ADR-0132 D5). The module that owns
 * the storage contributes one bean; the kernel never learns which tables exist.
 *
 * <p>The KEK version of a blob is the big-endian int32 at bytes 1..4 ({@link #versionOf}). A value that is not a
 * well-formed blob (another format, too short) has no version: it is in no count and no sweep, and never an error.
 */
public interface SealedStore {

    /** Names the store in a rotation's error; never a value. */
    String name();

    /** How many blobs are wrapped under a version below {@code version}. */
    long countBelow(int version);

    /**
     * Re-wraps up to {@code limit} blobs wrapped under a version below {@code targetVersion} with {@code rewrap},
     * walking the primary key in order from just after {@code after} ({@code null} starts at
     * the beginning), so a pass reads each row once. A row is updated only if its bytes are still the ones read, so a
     * concurrent writer wins and the row is picked up by the next pass.
     *
     * @return the rows updated and where to continue
     * @throws RewrapException when a blob cannot be re-wrapped
     */
    Batch rewrapBatch(Object after, int targetVersion, int limit, UnaryOperator<byte[]> rewrap);

    /**
     * @param updated the rows re-wrapped
     * @param last the key to pass as {@code after} for the next batch, or {@code null} when the store is exhausted
     */
    record Batch(int updated, Object last) {}

    /** How many blobs each KEK version protects. */
    Map<Integer, Long> countByVersion();

    /** SQL for the KEK version of the {@code bytea} expression {@code sealed}; null unless it is a well-formed blob. */
    static String versionOf(String sealed) {
        return "(CASE WHEN length(" + sealed + ") >= " + SecretVault.MIN_BLOB_BYTES + " AND get_byte(" + sealed
                + ", 0) = 1 THEN (get_byte(" + sealed + ", 1)::bigint << 24) | (get_byte(" + sealed
                + ", 2) << 16) | (get_byte(" + sealed + ", 3) << 8) | get_byte(" + sealed + ", 4) END)";
    }

    /** A blob that could not be re-wrapped: names the store and the row, never a value or a key. */
    final class RewrapException extends RuntimeException {

        public RewrapException(String store, String row, Throwable cause) {
            super("Store '" + store + "' row " + row + " could not be re-wrapped: " + cause.getMessage(), cause);
        }
    }
}
