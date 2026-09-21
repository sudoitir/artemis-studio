package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.web.BrokerConfigViews.DocumentView;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerXmlSnippets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0092 enumerates six paths a bridge's password could take out of Studio. It takes
 * none of them, because it is never in the declaration to begin with: the document
 * carries a reference and the vault carries the secret. One assertion per path — the
 * failure mode here is a leak that nothing else fails on.
 */
class BridgeCredentialDisclosureTest {

    private static final String SECRET = "hunter2-do-not-disclose";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BrokerConfigDocument doc(String credentialRef) {
        BridgeDecl b = new BridgeDecl(
                "orders-out",
                "orders.out",
                "orders.in",
                null,
                null,
                List.of("remote-a"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                credentialRef);
        return new BrokerConfigDocument(1, List.of(), List.of(), List.of(), List.of(), List.of(b));
    }

    /** 1 and 2: the stored document, and therefore every stored revision of it. */
    @Test
    void theDocumentHoldsAReferenceAndNoSecret() {
        String json = MAPPER.writeValueAsString(doc("orders-out-credential"));

        assertThat(json)
                .contains("orders-out-credential")
                .doesNotContain(SECRET)
                .doesNotContain("password");
    }

    /** 3: the difference between two revisions says the credential changed and shows neither value. */
    @Test
    void aRevisionDifferenceShowsOnlyTheReference() {
        BridgeDecl before = doc("old-credential").bridges().getFirst();
        BridgeDecl after = doc("new-credential").bridges().getFirst();

        assertThat(before).isNotEqualTo(after);
        assertThat(MAPPER.writeValueAsString(List.of(before, after)))
                .contains("old-credential")
                .contains("new-credential")
                .doesNotContain(SECRET);
    }

    /**
     * 4: the audit row's parameters. They are derived from the plan's steps, whose
     * {@code after} map is exactly what {@code bridgeMap} builds — and that omits the
     * credential, which is resolved from the vault at the broker call and nowhere
     * earlier. There is no value for {@code AuditParamsFilter} to withhold.
     */
    @Test
    void theAppliedDocumentCarriesNoCredentialAtAll() {
        var config = BrokerConfigPlanner.bridgeMap(
                doc("orders-out-credential").bridges().getFirst());

        assertThat(config).doesNotContainKeys("user", "password", "credential-ref", "credentialRef");
        assertThat(MAPPER.writeValueAsString(config)).doesNotContain("orders-out-credential");
    }

    /** 5: the MCP and REST view of the declaration. */
    @Test
    void theApiViewHoldsAReferenceAndNoSecret() {
        String json = MAPPER.writeValueAsString(DocumentView.of(doc("orders-out-credential")));

        assertThat(json).contains("\"credentialRef\":\"orders-out-credential\"").doesNotContain(SECRET);
    }

    /** 6: exported configuration, which is a file people paste into repositories. */
    @Test
    void theExportNamesTheCredentialToSupplyAndCarriesNone() {
        String xml = BrokerXmlCodec.write(doc("orders-out-credential"));

        assertThat(xml)
                .contains("Credential 'orders-out-credential'")
                .contains("${orders-out-credential.password}")
                .doesNotContain(SECRET);
        // Re-importing it does not resurrect a credential, and does not fail on the placeholder.
        BrokerXmlCodec.ParseResult back = BrokerXmlCodec.parse(xml);
        assertThat(back.errors()).isEmpty();
        assertThat(back.document().bridges().getFirst().credentialRef()).isNull();
        assertThat(back.unsupported())
                .extracting(BrokerXmlCodec.Unsupported::reason)
                .allMatch(r -> r.contains("vault"));
    }

    /** The capability snippet is the same file-shaped output, so it gets the same rule. */
    @Test
    void theBrokerXmlSnippetNamesTheCredentialToSupply() {
        String xml = BrokerXmlSnippets.forBridge(
                "orders-out", "orders.out", "orders.in", null, List.of("remote-a"), null, "orders-out-credential");

        assertThat(xml)
                .contains("Credential 'orders-out-credential'")
                .contains("${orders-out-credential.user}")
                .doesNotContain(SECRET);
    }
}
