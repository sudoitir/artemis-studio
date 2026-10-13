package io.github.sudoitir.artemisstudio.kernel.core.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * An optional read replica for heavy history reads and exports ({@code ReadRouting}).
 *
 * @param url the replica's JDBC URL; blank means no replica and every read uses the primary
 * @param username the replica's user, the primary's when blank
 * @param password the replica's password, the primary's when blank
 * @param poolSize connections the read pool keeps open, per Studio replica
 * @param maxLag how far the replica may lag the primary and still serve a read
 */
@ConfigurationProperties(prefix = "studio.database.read")
record DatabaseReadProperties(
        @DefaultValue("") String url,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("8") int poolSize,
        @DefaultValue("30s") Duration maxLag) {

    boolean configured() {
        return !url.isBlank();
    }
}
