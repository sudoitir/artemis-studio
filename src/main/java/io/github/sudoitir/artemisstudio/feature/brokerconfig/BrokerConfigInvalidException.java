package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.platform.mcp.McpReportable;
import java.util.List;
import java.util.stream.Collectors;

/** A declaration that cannot be saved or applied, with every reason and the field it belongs to. */
public class BrokerConfigInvalidException extends RuntimeException implements McpReportable {

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

    @Override
    public String mcpMessage() {
        return "The declaration is invalid: "
                + violations.stream().map(v -> v.path() + ": " + v.message()).collect(Collectors.joining("; "));
    }
}
