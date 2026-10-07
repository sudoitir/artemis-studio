package io.github.sudoitir.artemisstudio.kernel.approval;

/**
 * An approved request's replay reached the gate with a different operation, or a second time. Nothing ran; the
 * request ends refused.
 */
class ReplayMismatchException extends IllegalStateException {

    ReplayMismatchException(String message) {
        super(message);
    }
}
