package io.github.sudoitir.artemisstudio.platform.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernanceAuditParamsFilterTest {

    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final GovernanceAuditParamsFilter filter = new GovernanceAuditParamsFilter(policy);

    @BeforeEach
    void classifyCustomerEmail() {
        when(policy.classifies(any(), anyString())).thenReturn(false);
        when(policy.classifies(eq(Location.PROPERTY), eq("customerEmail"))).thenReturn(true);
        when(policy.governText(anyString())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aSelectorLiteralComparedWithAClassifiedPropertyIsRedacted() {
        assertThat(filter.text("customerEmail = 'jane' AND region = 'eu'"))
                .isEqualTo("customerEmail = '[redacted]' AND region = 'eu'");
    }

    @Test
    void aSqlLiteralComparedWithAClassifiedPropertyIsRedacted() {
        assertThat(filter.text("SELECT * FROM q WHERE props->>'customerEmail' LIKE '%jane%'"))
                .isEqualTo("SELECT * FROM q WHERE props->>'customerEmail' LIKE '[redacted]'");
    }

    @Test
    void aConsoleLiteralOnADottedPropertyIsRedacted() {
        assertThat(filter.text("SELECT * FROM \"ORDERS\" WHERE props.customerEmail = 'jane' AND props.region = 'eu'"))
                .isEqualTo("SELECT * FROM \"ORDERS\" WHERE props.customerEmail = '[redacted]' AND props.region = 'eu'");
    }

    @Test
    void nestedParametersAreWalkedAndKeysKept() {
        Map<String, ?> out = filter.filter(
                Map.of("filter", "customerEmail <> 'x'", "ids", List.of("customerEmail = 'y'"), "count", 3));

        assertThat(out.keySet()).containsExactlyInAnyOrder("filter", "ids", "count");
        assertThat(out.get("filter")).isEqualTo("customerEmail <> '[redacted]'");
        assertThat(out.get("ids")).isEqualTo(List.of("customerEmail = '[redacted]'"));
        assertThat(out.get("count")).isEqualTo(3);
    }
}
