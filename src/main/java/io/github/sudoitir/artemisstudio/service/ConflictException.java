package io.github.sudoitir.artemisstudio.service;

/** A mutation that is individually well-formed but conflicts with existing state. Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    private final String slug;

    public ConflictException(String slug, String message) {
        super(message);
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }
}
