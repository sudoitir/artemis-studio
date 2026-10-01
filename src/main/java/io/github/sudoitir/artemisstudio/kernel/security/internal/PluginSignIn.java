package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.security.CredentialIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.ExternalIdentity;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProvisioner;
import io.github.sudoitir.artemisstudio.kernel.security.PluginCredentialProvider;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.VerifiedIdentity;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The sign-in providers plugins offer (ADR-0153): the one bridge that holds them and the one
 * {@link IdentityProviders} contribution that lists them, so a plugin's provider is one more
 * credential provider on {@link LoginService}'s path and nothing downstream knows it is a
 * plugin's. A plugin answers with who the credentials identify; the adapter here turns that into an
 * {@link ExternalIdentity} under the provider id the plugin declared, so a plugin can only ever sign
 * in accounts keyed by its own provider. Every call into a plugin is bounded; a failed one is a
 * failed sign-in and is recorded for {@link PluginSignInHealthIndicator}.
 */
@Component
@Slf4j
public class PluginSignIn implements PluginBridge, IdentityProviders {

    static final Duration AUTHENTICATE_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REVALIDATE_TIMEOUT = Duration.ofSeconds(30);

    private static final int MAX_FIELD = 255;
    private static final int MAX_GROUPS = 1_000;

    /** A provider whose latest call failed, for operational health. */
    public record Failure(String plugin, String providerId, String reason, Instant at) {}

    private record Attached(PluginHandle handle, List<Provider> providers) {}

    private final IdentityProvisioner provisioner;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Attached> attached = new ConcurrentHashMap<>();
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();

    public PluginSignIn(IdentityProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public void attach(PluginHandle handle) {
        Map<String, String> declared = new HashMap<>();
        handle.descriptor().identityProviders().forEach(p -> declared.put(p.id(), p.label()));
        Map<String, PluginCredentialProvider> beans = new HashMap<>();
        for (PluginCredentialProvider bean :
                handle.beansOfType(PluginCredentialProvider.class).values()) {
            String id = call(handle, bean::id, AUTHENTICATE_TIMEOUT);
            if (!declared.containsKey(id)) {
                throw new IllegalStateException(
                        "Sign-in provider \"%s\" is not declared under identityProviders in plugin.json".formatted(id));
            }
            if (beans.put(id, bean) != null) {
                throw new IllegalStateException("Sign-in provider \"%s\" has more than one bean".formatted(id));
            }
        }
        for (String id : declared.keySet()) {
            if (!beans.containsKey(id)) {
                throw new IllegalStateException(
                        "Sign-in provider \"%s\" is declared in plugin.json but the plugin has no PluginCredentialProvider bean for it"
                                .formatted(id));
            }
        }
        if (declared.isEmpty()) {
            return;
        }
        List<Provider> providers = declared.entrySet().stream()
                .map(e -> new Provider(handle, e.getKey(), e.getValue(), beans.get(e.getKey())))
                .toList();
        attached.put(handle.id(), new Attached(handle, providers));
    }

    @Override
    public void detach(PluginHandle handle) {
        Attached current = attached.get(handle.id());
        if (current == null || current.handle() != handle || !attached.remove(handle.id(), current)) {
            return;
        }
        current.providers().forEach(p -> failures.remove(p.id()));
    }

    /** The providers of every attached plugin whose signer is trusted now. */
    @Override
    public List<? extends IdentityProvider> providers() {
        return verified();
    }

    /** As {@link #providers()}, as the plugin providers themselves. */
    public List<Provider> verified() {
        List<Provider> out = new ArrayList<>();
        for (Attached a : attached.values()) {
            if (a.handle().verified()) {
                out.addAll(a.providers());
            }
        }
        return out;
    }

    /** Providers whose latest call failed. */
    public List<Failure> failures() {
        return List.copyOf(failures.values());
    }

    /** Calls into the plugin on a virtual thread, abandoning it after {@code timeout}. */
    <T> T call(PluginHandle handle, Callable<T> body, Duration timeout) {
        Future<T> future = calls.submit(() -> handle.runInPlugin(body));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            future.cancel(true);
            throw new IllegalStateException("did not answer within " + timeout.toSeconds() + " s");
        } catch (InterruptedException _) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (ExecutionException e) {
            throw new IllegalStateException(String.valueOf(e.getCause()), e.getCause());
        }
    }

    private void failed(Provider provider, String reason) {
        failures.put(provider.id(), new Failure(provider.handle().id(), provider.id(), reason, Instant.now()));
        log.warn("Sign-in provider {} failed: {}", provider.id(), reason);
    }

    private void succeeded(Provider provider) {
        failures.remove(provider.id());
    }

    /**
     * One plugin sign-in as a credential provider: the id and label the plugin declared, and the
     * plugin's answer turned into a Studio user.
     */
    public final class Provider implements CredentialIdentityProvider {

        private final PluginHandle handle;
        private final String id;
        private final String label;
        private final PluginCredentialProvider bean;

        private Provider(PluginHandle handle, String id, String label, PluginCredentialProvider bean) {
            this.handle = handle;
            this.id = id;
            this.label = label;
            this.bean = bean;
        }

        PluginHandle handle() {
            return handle;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return label;
        }

        /**
         * Asks the plugin, with the username as the plugin knows it: an account that shares a name with
         * another provider's is stored as {@code name@<provider id>} (the stored name is what a step-up
         * is given), and the qualifier is the plugin's no concern.
         */
        @Override
        public Optional<StudioPrincipal> authenticate(String username, String password) {
            if (!handle.verified()) {
                return Optional.empty();
            }
            String external =
                    username.endsWith("@" + id) ? username.substring(0, username.length() - id.length() - 1) : username;
            Optional<VerifiedIdentity> answer;
            try {
                answer = call(handle, () -> bean.authenticate(external, password), AUTHENTICATE_TIMEOUT);
            } catch (RuntimeException e) {
                failed(this, e.getMessage());
                return Optional.empty();
            }
            if (answer == null) {
                failed(this, "answered null instead of an identity or empty");
                return Optional.empty();
            }
            succeeded(this);
            if (answer.isEmpty()) {
                return Optional.empty();
            }
            VerifiedIdentity identity = answer.get();
            String invalid = invalid(identity);
            if (invalid != null) {
                failed(this, "answered an invalid identity: " + invalid);
                return Optional.empty();
            }
            return provisioner.provision(new ExternalIdentity(
                    id, identity.subject(), identity.username(), identity.email(), identity.groups()));
        }

        /** The subjects of this provider's accounts the plugin no longer vouches for; empty on any failure. */
        Set<String> noLongerValid(Set<String> subjects) {
            if (!handle.verified()) {
                return Set.of();
            }
            try {
                Set<String> revoked = call(handle, () -> bean.noLongerValid(subjects), REVALIDATE_TIMEOUT);
                succeeded(this);
                return revoked == null ? Set.of() : revoked;
            } catch (RuntimeException e) {
                failed(this, e.getMessage());
                return Set.of();
            }
        }
    }

    /** Why the plugin's answer is not acceptable, or null. */
    private static String invalid(VerifiedIdentity identity) {
        if (isBlank(identity.subject()) || isBlank(identity.username())) {
            return "a blank subject or username";
        }
        if (tooLong(identity.subject()) || tooLong(identity.username()) || tooLong(identity.email())) {
            return "a field longer than " + MAX_FIELD + " characters";
        }
        if (identity.groups().size() > MAX_GROUPS) {
            return "more than " + MAX_GROUPS + " groups";
        }
        if (identity.groups().stream().anyMatch(PluginSignIn::tooLong)) {
            return "a group name longer than " + MAX_FIELD + " characters";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > MAX_FIELD;
    }

    @PreDestroy
    void close() {
        calls.shutdownNow();
    }
}
