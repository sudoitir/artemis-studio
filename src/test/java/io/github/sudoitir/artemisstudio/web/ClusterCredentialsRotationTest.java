package io.github.sudoitir.artemisstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.BrokerCredentialEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerCredentialRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.security.SecretVault;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code PUT /api/v1/clusters/{id}/credentials}: re-encrypts, audits in-transaction, leaks no secret. */
class ClusterCredentialsRotationTest extends PostgresIntegrationTest {

    private static final String JOLOKIA_BASIC = "JOLOKIA_BASIC";

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerCredentialRepository credentials;

    @Autowired
    AuditEventRepository audits;

    @Autowired
    SecretVault vault;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        SecretVault.Sealed sealed = vault.encrypt(clusterId, JOLOKIA_BASIC, "old-secret");
        credentials.save(
                new BrokerCredentialEntity(clusterId, JOLOKIA_BASIC, "old-user", sealed.ciphertext(), sealed.nonce()));
    }

    @Test
    void rotatingReEncryptsAndAuditsWithoutReturningTheSecret() throws Exception {
        String body = mvc.perform(put("/api/v1/clusters/{id}/credentials", clusterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-user\",\"password\":\"new-secret\"}"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("new-secret");

        BrokerCredentialEntity stored =
                credentials.findByClusterIdAndKind(clusterId, JOLOKIA_BASIC).orElseThrow();
        assertThat(stored.getUsername()).isEqualTo("new-user");
        assertThat(vault.decrypt(clusterId, JOLOKIA_BASIC, stored.getSecretCt(), stored.getSecretNonce()))
                .isEqualTo("new-secret");

        assertThat(audits.findAll()).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo("ROTATE_CREDENTIALS");
            assertThat(a.getOutcome()).isEqualTo("SUCCESS");
        });
    }
}
