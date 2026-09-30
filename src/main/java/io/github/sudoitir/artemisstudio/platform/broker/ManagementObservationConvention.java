package io.github.sudoitir.artemisstudio.platform.broker;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import java.io.IOException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.ClientRequestObservationConvention;

/**
 * Names a Jolokia call {@code studio.broker.management}, tagged with the node's {@code host:port}
 * ({@link NodeAddress}) and its {@code outcome}. Nothing of the request is recorded: not its URL,
 * which may hold credentials, and not its body, which carries MBean arguments.
 */
final class ManagementObservationConvention implements ClientRequestObservationConvention {

    static final String NAME = "studio.broker.management";
    private static final String ERROR = "ERROR";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getContextualName(ClientRequestObservationContext context) {
        return "jolokia";
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
        String node = context.getCarrier() == null
                ? NodeAddress.UNKNOWN
                : NodeAddress.hostPort(context.getCarrier().getURI().toString());
        return KeyValues.of(KeyValue.of("node", node), KeyValue.of("outcome", outcome(context)));
    }

    private static String outcome(ClientRequestObservationContext context) {
        if (context.getError() != null || context.getResponse() == null) {
            return ERROR;
        }
        try {
            HttpStatusCode status = context.getResponse().getStatusCode();
            return status.is2xxSuccessful() ? "SUCCESS" : ERROR;
        } catch (IOException _) {
            return ERROR;
        }
    }
}
