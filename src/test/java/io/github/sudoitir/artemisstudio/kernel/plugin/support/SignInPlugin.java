package io.github.sudoitir.artemisstudio.kernel.plugin.support;

import java.util.List;
import java.util.Map;

/** A fixture plugin offering one sign-in provider, answered by a {@link SignInProbe} directory. */
public final class SignInPlugin {

    private SignInPlugin() {}

    /** The provider id a plugin named {@code pluginId} declares for {@code name}. */
    public static String providerId(String pluginId, String name) {
        return pluginId + ":" + name;
    }

    /**
     * A plugin declaring {@code <pluginId>:corp} ("Corporate directory") and exposing the bean for it.
     *
     * @param revalidates whether the bean answers {@code noLongerValid}; without it the default (nothing) applies
     */
    public static PluginJarBuilder jar(String pluginId, boolean revalidates) {
        PluginJarBuilder builder = new PluginJarBuilder(pluginId);
        return declare(builder, providerId(pluginId, "corp"), "Corporate directory")
                .source(
                        builder.basePackage() + ".Directory",
                        bean(builder.basePackage(), pluginId + ":corp", revalidates));
    }

    /** Adds a declared provider to {@code builder}, without a bean for it. */
    public static PluginJarBuilder declare(PluginJarBuilder builder, String providerId, String label) {
        return builder.descriptorField("identityProviders", List.of(Map.of("id", providerId, "label", label)));
    }

    public static String bean(String basePackage, String providerId, boolean revalidates) {
        return """
                package %s;
                import io.github.sudoitir.artemisstudio.kernel.plugin.support.SignInProbe;
                import io.github.sudoitir.artemisstudio.kernel.security.PluginCredentialProvider;
                import io.github.sudoitir.artemisstudio.kernel.security.VerifiedIdentity;
                import java.util.Optional;
                import java.util.Set;
                import org.springframework.stereotype.Component;
                @Component
                public class Directory implements PluginCredentialProvider {
                    public String id() { return "%s"; }
                    public Optional<VerifiedIdentity> authenticate(String username, String password) {
                        return SignInProbe.authenticate(id(), username, password);
                    }
                    %s
                }
                """.formatted(basePackage, providerId, revalidates ? """
                        public Set<String> noLongerValid(Set<String> subjects) {
                            return SignInProbe.noLongerValid(id(), subjects);
                        }
                        """ : "");
    }
}
