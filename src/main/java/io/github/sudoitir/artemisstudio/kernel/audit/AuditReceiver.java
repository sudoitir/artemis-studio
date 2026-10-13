package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * Receives every audit record, unfiltered. A plugin that declares {@code audit.receive} in its
 * manifest provides exactly one.
 *
 * <p>Delivery is at least once and in commit order. Returning normally acknowledges the whole batch;
 * throwing, or taking longer than the delivery timeout, delivers the same batch again later, with
 * a growing delay. After a restart or a failed acknowledgement a batch comes again, so a record is
 * identified by its {@code id} and {@code position} and a receiver must ignore one it already has. A
 * record whose outcome was filled in later arrives again at a later position.
 */
@PluginApi
public interface AuditReceiver {

    void receive(AuditDelivery delivery);
}
