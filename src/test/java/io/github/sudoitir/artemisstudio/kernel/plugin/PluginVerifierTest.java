package io.github.sudoitir.artemisstudio.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The author-side check says what Studio's upload would say, before anyone uploads (ADR-0102). */
class PluginVerifierTest {

    private static String run(Path jar, int expectedExit) throws Exception {
        var out = new ByteArrayOutputStream();
        int exit = PluginVerifier.verify(jar, "2026.09.0", new PrintStream(out, true, StandardCharsets.UTF_8));
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
}
