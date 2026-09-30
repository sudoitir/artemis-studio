package io.github.sudoitir.artemisstudio.kernel.security;

/** A blob that could not be re-wrapped: names the store and the row, never a value or a key. */
public final class RewrapException extends RuntimeException {

    public RewrapException(String store, String row, Throwable cause) {
        super("Store '" + store + "' row " + row + " could not be re-wrapped: " + cause.getMessage(), cause);
    }
}
