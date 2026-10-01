package io.github.sudoitir.artemisstudio.feature.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.KeySource;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrust;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PublisherKeys;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Signer;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The reconciliation of the trusted key table with the configured keys, without a database. */
class TrustedKeyReconcilerTest {

    private static final Base64.Encoder PEM = Base64.getMimeEncoder(64, "\n".getBytes());

    private final PluginTrust trust = mock(PluginTrust.class);
    private final AuditService audit = mock(AuditService.class);

    private TrustedKeyReconciler reconciler(PluginProperties.TrustedKey... keys) {
        when(audit.begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false)))
                .thenReturn(mock(AuditEvent.class));
        return new TrustedKeyReconciler(
                new PluginProperties(null, false, null, null, null, null, List.of(keys)), trust, audit);
    }

    /** A made-up publisher key, generated here. */
    private static String publicKeyPem() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        byte[] der = generator.generateKeyPair().getPublic().getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n" + PEM.encodeToString(der) + "\n-----END PUBLIC KEY-----\n";
    }

    private static PluginTrust.TrustedKey stored(String pem, String name, KeySource source) {
        Signer signer = PublisherKeys.parse(pem);
        return new PluginTrust.TrustedKey(signer.fingerprint(), name, "", Instant.now(), "someone", source);
    }

    @Test
    void aConfiguredKeyIsAddedAndAudited() throws Exception {
        String pem = publicKeyPem();
        String fingerprint = PublisherKeys.parse(pem).fingerprint();
        when(trust.keys()).thenReturn(List.of());

        reconciler(new PluginProperties.TrustedKey("Example Publisher", pem)).reconcile();

        verify(trust).pin(eq("Example Publisher"), any(Signer.class), eq("configuration"));
        var actor = ArgumentCaptor.forClass(Actor.class);
        var params = ArgumentCaptor.forClass(Map.class);
        var order = inOrder(audit, trust);
        order.verify(audit)
                .begin(
                        actor.capture(),
                        eq("PLUGIN_KEY_ADD"),
                        eq("plugin"),
                        eq("Example Publisher"),
                        any(),
                        any(),
                        params.capture(),
                        eq(false));
        order.verify(trust).pin(anyString(), any(), anyString());
        order.verify(audit).succeed(any(), eq(1L));
        assertThat(actor.getValue().username()).isEqualTo("configuration");
        assertThat(params.getValue()).containsEntry("fingerprint", fingerprint).containsEntry("change", "add");
    }

    @Test
    void anAdministratorsKeyBecomesAConfiguredOne() throws Exception {
        String pem = publicKeyPem();
        when(trust.keys()).thenReturn(List.of(stored(pem, "Added by hand", KeySource.ADMIN)));

        reconciler(new PluginProperties.TrustedKey("Example Publisher", pem)).reconcile();

        verify(trust).pin(eq("Example Publisher"), any(Signer.class), eq("configuration"));
        var params = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .begin(
                        any(),
                        eq("PLUGIN_KEY_ADD"),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        params.capture(),
                        eq(false));
        assertThat(params.getValue()).containsEntry("change", "convert");
    }

    @Test
    void aKeyThatIsAlreadyConfiguredIsLeftAlone() throws Exception {
        String pem = publicKeyPem();
        when(trust.keys()).thenReturn(List.of(stored(pem, "Example Publisher", KeySource.CONFIGURATION)));

        reconciler(new PluginProperties.TrustedKey("Example Publisher", pem)).reconcile();

        verify(trust, never()).pin(anyString(), any(), anyString());
        verify(trust, never()).unpin(anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void aRenamedConfiguredKeyTakesTheNewName() throws Exception {
        String pem = publicKeyPem();
        when(trust.keys()).thenReturn(List.of(stored(pem, "Old name", KeySource.CONFIGURATION)));

        reconciler(new PluginProperties.TrustedKey("Example Publisher", pem)).reconcile();

        verify(trust).pin(eq("Example Publisher"), any(Signer.class), eq("configuration"));
    }

    @Test
    void aConfiguredKeyThatIsNoLongerListedIsRemovedAndAnAdministratorsKeyIsNot() throws Exception {
        PluginTrust.TrustedKey gone = stored(publicKeyPem(), "Example Publisher", KeySource.CONFIGURATION);
        PluginTrust.TrustedKey mine = stored(publicKeyPem(), "Added by hand", KeySource.ADMIN);
        when(trust.keys()).thenReturn(List.of(gone, mine));

        reconciler().reconcile();

        verify(trust).unpin(gone.fingerprint());
        verify(trust, never()).unpin(mine.fingerprint());
        var params = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .begin(
                        any(),
                        eq("PLUGIN_KEY_REMOVE"),
                        eq("plugin"),
                        eq(gone.fingerprint()),
                        any(),
                        any(),
                        params.capture(),
                        eq(false));
        assertThat(params.getValue()).containsEntry("fingerprint", gone.fingerprint());
        verify(audit).succeed(any(), eq(1L));
    }

    @Test
    void aFailedChangeIsRecordedAndStopsStartup() throws Exception {
        when(trust.keys()).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("database is down"))
                .when(trust)
                .pin(anyString(), any(), anyString());

        var reconciler = reconciler(new PluginProperties.TrustedKey("Example Publisher", publicKeyPem()));

        assertThatThrownBy(reconciler::reconcile).hasMessage("database is down");
        verify(audit).fail(any(), eq("database is down"));
    }

    @Test
    void twoEntriesWithTheSameKeyFailStartupNamingBoth() throws Exception {
        String pem = publicKeyPem();
        var reconciler = reconciler(
                new PluginProperties.TrustedKey("Example Publisher", pem),
                new PluginProperties.TrustedKey("Example Publisher again", pem));

        assertThatThrownBy(reconciler::reconcile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artemis-studio.plugins.trusted-keys[1]")
                .hasMessageContaining("trusted-keys[0]")
                .hasMessageContaining(PublisherKeys.parse(pem).fingerprint());
        verifyNoInteractions(trust, audit);
    }

    @Test
    void anEntryThatCannotBeParsedFailsStartupNamingItsIndexAndReason() throws Exception {
        var reconciler = reconciler(
                new PluginProperties.TrustedKey("Example Publisher", publicKeyPem()),
                new PluginProperties.TrustedKey("Broken", "not a key"));

        assertThatThrownBy(reconciler::reconcile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artemis-studio.plugins.trusted-keys[1]")
                .hasMessageContaining("Broken")
                .hasMessageContaining("Expected a PEM certificate");
        verifyNoInteractions(trust, audit);
    }

    @Test
    void anEntryWithoutANameFailsStartup() throws Exception {
        var reconciler = reconciler(new PluginProperties.TrustedKey(" ", publicKeyPem()));

        assertThatThrownBy(reconciler::reconcile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artemis-studio.plugins.trusted-keys[0]")
                .hasMessageContaining("name is required");
    }

    @Test
    void anEntryWithoutAKeyFailsStartup() {
        var reconciler = reconciler(new PluginProperties.TrustedKey("Example Publisher", null));

        assertThatThrownBy(reconciler::reconcile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-keys[0]")
                .hasMessageContaining("pem is required");
    }
}
