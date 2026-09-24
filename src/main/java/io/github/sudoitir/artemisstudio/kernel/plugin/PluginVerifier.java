package io.github.sudoitir.artemisstudio.kernel.plugin;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.ChangesetInfo;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.PluginValidator;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.ValidationReport;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

/**
 * Checks a built plugin jar exactly as Studio will when it is uploaded (ADR-0102): the same
 * validator, run from the plugin's build instead of Studio's admin screen, so an author learns
 * about a refusal before an operator does. It also warns about each database change that has no
 * rollback, since that makes Roll back unavailable after an update.
 *
 * <pre>java -cp artemis-studio.jar:&lt;its dependencies&gt; io.github.sudoitir.artemisstudio.kernel.plugin.PluginVerifier target/my-plugin.jar</pre>
 *
 * The Studio version the jar's {@code studio.since..until} is checked against is the version of
 * the {@code artemis-studio} jar on the classpath — the one the plugin compiled against — or
 * {@code -Dartemis-studio.version=YYYY.MM.N}. Exits 1 when Studio would refuse the jar.
 */
@PluginApi
public final class PluginVerifier {

    private PluginVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: PluginVerifier <plugin.jar>");
            System.exit(2);
        }
        System.exit(verify(Path.of(args[0]), System.getProperty("artemis-studio.version"), System.out));
    }

    /** Prints the verdict to {@code out}; returns the process exit code: 0 accepted, 1 refused, 2 unreadable. */
    public static int verify(Path jar, String studioVersionOverride, PrintStream out) throws IOException {
        if (!Files.isRegularFile(jar)) {
            out.println("No such file: " + jar);
            return 2;
        }
        StudioVersion studioVersion = new StudioVersion(
                buildProperties(), new PluginProperties(studioVersionOverride, false, null, null, null, null));
        ValidationReport report =
                new PluginValidator(new PluginDescriptorParser(), studioVersion).validate(jar, Set.of());

        out.println("Checked " + jar.getFileName() + " against Studio "
                + studioVersion.current().map(Object::toString).orElse("(unknown version: range not checked)"));
        for (Violation v : report.violations()) {
            out.println((v.severity() == Violation.Severity.ERROR ? "ERROR   " : "WARNING ") + "[" + v.code() + "] "
                    + v.message());
            if (v.authorFix() != null && !v.authorFix().isBlank()) {
                out.println("        fix: " + v.authorFix());
            }
        }
        List<ChangesetInfo> irreversible =
                report.changesets().stream().filter(c -> !c.reversible()).toList();
        for (ChangesetInfo c : irreversible) {
            out.println("WARNING [changeset-irreversible] " + c.author() + ":" + c.id()
                    + " has no rollback; an update that applies it cannot be rolled back.");
            out.println("        fix: add a --rollback (formatted SQL) or <rollback> (XML) to it.");
        }
        if (!report.valid()) {
            out.println("Refused: Studio would not install this jar.");
            return 1;
        }
        out.println("Accepted: " + report.descriptor().id() + " "
                + report.descriptor().version() + ".");
        return 0;
    }

    /** {@code META-INF/build-info.properties} of the Studio jar on the classpath, when there is one. */
    private static org.springframework.beans.factory.ObjectProvider<BuildProperties> buildProperties()
            throws IOException {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        try (InputStream in = PluginVerifier.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in != null) {
                Properties raw = new Properties();
                raw.load(in);
                Properties build = new Properties();
                raw.forEach((key, value) -> {
                    String name = key.toString();
                    if (name.startsWith("build.")) {
                        build.put(name.substring("build.".length()), value);
                    }
                });
                beans.addBean("buildProperties", new BuildProperties(build));
            }
        }
        return beans.getBeanProvider(BuildProperties.class);
    }
}
