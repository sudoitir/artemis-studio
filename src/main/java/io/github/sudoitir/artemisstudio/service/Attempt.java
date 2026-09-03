package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;

/**
 * The result of an operation that touches a broker and must record an audit
 * outcome either way: {@link Ok} carries the value; {@link Failed} carries a
 * classified connection error. The service commits its transaction (audit row
 * included) before returning, so the controller turns a {@link Failed} into a
 * {@code ProblemDetail} without rolling anything back.
 */
public sealed interface Attempt<T> {

    record Ok<T>(T value) implements Attempt<T> {}

    record Failed<T>(BrokerConnectionException.Kind kind, String detail) implements Attempt<T> {}
}
