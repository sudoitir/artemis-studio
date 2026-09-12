package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.domain.brokerconfig.Violation;
import java.util.List;

/** A declaration that cannot be saved or applied, with every reason and the field it belongs to. */
public class BrokerConfigInvalidException extends RuntimeException {

    private final List<Violation> violations;

    public BrokerConfigInvalidException(List<Violation> violations) {
        super(
                violations.size() == 1
                        ? violations.getFirst().message()
                        : violations.size() + " problems with the declaration; the first: "
                                + violations.getFirst().message());
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
