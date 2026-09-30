package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.platform.mcp.McpReportable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** A declaration that cannot be saved or applied, with every reason and the field it belongs to. */
public class BrokerConfigInvalidException extends RuntimeException implements McpReportable {

    private final ArrayList<Violation> violations;

    public BrokerConfigInvalidException(List<Violation> violations) {
        super(
                violations.size() == 1
                        ? violations.getFirst().message()
                        : violations.size() + " problems with the declaration; the first: "
                                + violations.getFirst().message());
        this.violations = new ArrayList<>(violations);
    }

    public List<Violation> violations() {
        return Collections.unmodifiableList(violations);
    }

    @Override
    public String mcpMessage() {
        return "The declaration is invalid: "
                + violations.stream().map(v -> v.path() + ": " + v.message()).collect(Collectors.joining("; "));
    }
}
