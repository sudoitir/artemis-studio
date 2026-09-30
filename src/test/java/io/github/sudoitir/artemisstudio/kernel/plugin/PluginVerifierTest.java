package io.github.sudoitir.artemisstudio.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The author-side check says what Studio's upload would say, before anyone uploads (ADR-0102). */
class PluginVerifierTest {

    @TempDir
    Path dir;

    private static String run(Path jar, int expectedExit) throws Exception {
        return run(jar, null, expectedExit);
    }

    private static String run(Path jar, Path certificate, int expectedExit) throws Exception {
        var out = new ByteArrayOutputStream();
        int exit = PluginVerifier.verify(
                jar, "2026.09.0", certificate, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(exit).as(text).isEqualTo(expectedExit);
        return text;
    }

    @Test
    void acceptsAValidPlugin() throws Exception {
        assertThat(run(new PluginJarBuilder("acme-notes").build(), 0)).contains("Accepted: acme-notes 1.0.0.");
    }

    @Test
    void refusesWhatTheUploadWouldRefuseAndSaysHowToFixIt() throws Exception {
        String text = run(
                new PluginJarBuilder("acme-notes")
                        .manifestAttribute("Class-Path", "lib/x.jar")
                        .build(),
                1);
        assertThat(text).contains("ERROR").contains("fix:").contains("Refused");
    }

    @Test
    void warnsThatAnIrreversibleChangeMakesRollBackUnavailable() throws Exception {
        var jar = new PluginJarBuilder("acme-notes").changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                            <changeSet id="1" author="acme">
                                <sql>CREATE TABLE note (id uuid)</sql>
                            </changeSet>
                        </databaseChangeLog>
                        """).build();
        assertThat(run(jar, 0)).contains("[changeset-irreversible] acme:1");
    }

    /** What a publisher gets from {@code keytool -exportcert -rfc}. */
    private Path pem(TestSigningKeys.Key key) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.certificate().getEncoded());
        return Files.writeString(
                dir.resolve(key.fingerprint().replace(':', '-') + ".pem"),
                "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n");
    }

    @Test
    void namesTheSignerOfASignedJar() throws Exception {
        assertThat(run(new PluginJarBuilder("acme-notes").build(), 0))
                .contains("Signed by ")
                .contains("key " + TestSigningKeys.PUBLISHER.fingerprint());
    }

    @Test
    void acceptsAJarSignedByThePublishedKey() throws Exception {
        assertThat(run(new PluginJarBuilder("acme-notes").build(), pem(TestSigningKeys.PUBLISHER), 0))
                .contains("Accepted");
    }

    @Test
    void refusesAJarSignedByAnotherKeyThanThePublishedOne() throws Exception {
        var jar = new PluginJarBuilder("acme-notes").build();
        assertThat(run(jar, pem(TestSigningKeys.OTHER), 1))
                .contains("[publisher-key-mismatch]")
                .contains(TestSigningKeys.OTHER.fingerprint());
    }

    @Test
    void refusesAnUnsignedJarWhenAPublishedKeyIsGiven() throws Exception {
        var jar = new PluginJarBuilder("acme-notes").unsigned().build();
        assertThat(run(jar, pem(TestSigningKeys.PUBLISHER), 1)).contains("[publisher-key-mismatch]");
    }

    @Test
    void warnsAboutAnUnsignedJarWithoutAPublishedKey() throws Exception {
        var jar = new PluginJarBuilder("acme-notes").unsigned().build();
        assertThat(run(jar, 0)).contains("WARNING [plugin-unsigned]").contains("Accepted");
    }
}
