package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import io.github.sudoitir.artemisstudio.kernel.plugin.CalVer;
import io.github.sudoitir.artemisstudio.kernel.plugin.Contract;
import io.github.sudoitir.artemisstudio.kernel.plugin.StudioVersion;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * Inspects an uploaded plugin jar before anything is stored or run (design.md §3,
 * specs/plugin-runtime). No plugin code is ever executed or linked: the jar is opened with
 * {@link JarFile}, and bytecode is read with the JDK's {@code java.lang.classfile} API. The same
 * validator runs again before every activation.
 *
 * <p>Every violation names what is wrong and what the author must change; the caller decides
 * what to do with a {@link ValidationReport} that is not {@linkplain ValidationReport#valid()
 * valid}.
 */
@Component
public class PluginValidator {

    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    static final long MAX_READ_BYTES = 250L * 1024 * 1024;
    static final int MAX_ENTRIES = 20_000;
    static final long MAX_RATIO = 100;

    private static final Set<String> DENIED_MANIFEST_ATTRIBUTES =
            Set.of("Class-Path", "Launcher-Agent-Class", "Add-Opens", "Add-Exports", "Enable-Native-Access");

    /** Packages no plugin may claim as, or nest its {@code basePackage} under. */
    private static final List<String> RESERVED_PREFIXES = List.of(
            "io.github.sudoitir.artemisstudio",
            "java.",
            "javax.",
            "jakarta.",
            "org.springframework.",
            "com.fasterxml.",
            "tools.jackson.",
            "org.hibernate.",
            "liquibase.",
            "org.apache.activemq.");

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)+$");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    private final PluginDescriptorParser descriptorParser;
    private final StudioVersion studioVersion;

    public PluginValidator(PluginDescriptorParser descriptorParser, StudioVersion studioVersion) {
        this.descriptorParser = descriptorParser;
        this.studioVersion = studioVersion;
    }

    /**
     * @param jarFile the jar to inspect, already on local disk (a temp file, not the upload stream)
     * @param otherBasePackages the {@code basePackage} of every other installed plugin, for the
     *     overlap check
     */
    public ValidationReport validate(Path jarFile, Set<String> otherBasePackages) {
        List<Violation> violations = new ArrayList<>();
        long size;
        try {
            size = Files.size(jarFile);
        } catch (IOException e) {
            violations.add(new Violation(
                    "jar-unreadable", "The jar could not be read: " + e.getMessage(), "Re-upload the jar."));
            return new ValidationReport(null, violations);
        }
        if (size > MAX_FILE_BYTES) {
            violations.add(new Violation(
                    "jar-too-large",
                    "The jar is %d bytes, over the %d byte limit.".formatted(size, MAX_FILE_BYTES),
                    "Shrink the jar — relocate only the libraries you actually use, and shade with minimization."));
            return new ValidationReport(null, violations);
        }

        List<String> entryNames = scanEntries(jarFile, violations);
        if (entryNames == null) {
            // A structural problem (zip slip, a nested archive, an oversize entry) stops here:
            // nothing past this point can be trusted to describe the jar's real contents.
            return new ValidationReport(null, violations);
        }

        try (JarFile jar = new JarFile(jarFile.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
            if (!consistent(jar, entryNames, violations)) {
                return new ValidationReport(null, violations);
            }
            checkManifest(jar, violations);
            PluginDescriptor descriptor = readDescriptor(jar, violations);
            if (descriptor == null) {
                return new ValidationReport(null, violations);
            }
            checkAllowlist(entryNames, descriptor, violations);
            checkDescriptorFields(descriptor, otherBasePackages, violations);
            new BytecodeChecks(descriptor).check(jar, entryNames, violations);
            List<ChangesetInfo> changesets = new LiquibasePreflight(descriptor).check(jar, entryNames, violations);
            return new ValidationReport(descriptor, violations, changesets);
        } catch (IOException e) {
            violations.add(new Violation(
                    "jar-unreadable", "The jar could not be opened: " + e.getMessage(), "Re-upload the jar."));
            return new ValidationReport(null, violations);
        }
    }

    /**
     * The class loader reads a jar through its central directory; the scan above read its local
     * headers. An archive whose two views disagree (an entry only one of them lists, or a different
     * compressed size) is a zip-confusion payload — what was checked would not be what is loaded —
     * so it is refused. The central directory also supplies the compression ratio for entries the
     * stream could not size.
     */
    private boolean consistent(JarFile jar, List<String> scanned, List<Violation> violations) {
        List<String> central = jar.stream().map(ZipEntry::getName).sorted().toList();
        if (!central.equals(scanned.stream().sorted().toList())) {
            violations.add(new Violation(
                    "jar-inconsistent",
                    "The jar's central directory and its entries do not list the same files.",
                    "Rebuild the jar with a standard tool (Maven or the JDK's jar); do not edit it by hand."));
            return false;
        }
        for (ZipEntry entry : jar.stream().toList()) {
            long compressed = entry.getCompressedSize();
            if (compressed > 0 && entry.getSize() / compressed > MAX_RATIO) {
                violations.add(new Violation(
                        "jar-compression-ratio",
                        "\"%s\" compresses at more than %d:1.".formatted(entry.getName(), MAX_RATIO),
                        "Do not ship highly compressible padding; store the file uncompressed or remove it."));
                return false;
            }
        }
        return true;
    }

    /**
     * A raw, sequential scan for zip-slip and zip-bomb shapes, done before the jar is trusted to
     * {@link JarFile}. Returns the entry names, or {@code null} once a structural violation has
     * been recorded (the caller stops there).
     *
     * <p>ponytail: per-entry compressed size can read as {@code -1} while streaming a deflated
     * entry; the ratio check is then skipped for that one entry rather than guessed at. The total
     * 250&nbsp;MB read cap still bounds the damage.
     */
    private List<String> scanEntries(Path jarFile, List<Violation> violations) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long totalRead = 0;
        int count = 0;
        try (var in = Files.newInputStream(jarFile);
                ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    violations.add(new Violation(
                            "jar-too-many-entries",
                            "The jar has more than %d entries.".formatted(MAX_ENTRIES),
                            "Remove unused resources from the jar."));
                    return null;
                }
                String name = entry.getName();
                if (!isSafeName(name)) {
                    violations.add(new Violation(
                            "jar-unsafe-entry-name",
                            "\"%s\" is an absolute, backslash or path-traversal entry name.".formatted(name),
                            "Rebuild the jar with a standard zip tool; do not hand-craft entries."));
                    return null;
                }
                if (!seen.add(name)) {
                    violations.add(new Violation(
                            "jar-duplicate-entry",
                            "\"%s\" appears more than once in the jar.".formatted(name),
                            "Rebuild the jar without duplicate entries."));
                    return null;
                }
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!entry.isDirectory() && (lower.endsWith(".jar") || lower.endsWith(".zip"))) {
                    violations.add(
                            new Violation(
                                    "jar-nested-archive",
                                    "\"%s\" is a nested jar or zip.".formatted(name),
                                    "Shade and relocate dependencies into this jar's own classes instead of bundling nested archives."));
                    return null;
                }
                long entryRead = discard(zis, MAX_READ_BYTES - totalRead);
                totalRead += entryRead;
                if (totalRead > MAX_READ_BYTES) {
                    violations.add(new Violation(
                            "jar-too-much-content",
                            "The jar decompresses to more than %d bytes.".formatted(MAX_READ_BYTES),
                            "Shrink the jar's contents."));
                    return null;
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && entryRead / compressed > MAX_RATIO) {
                    violations.add(new Violation(
                            "jar-compression-ratio",
                            "\"%s\" compresses at more than %d:1.".formatted(name, MAX_RATIO),
                            "Remove the entry; a legitimate class or resource does not compress this far."));
                    return null;
                }
                names.add(name);
            }
        } catch (IOException e) {
            violations.add(new Violation(
                    "jar-unreadable", "The jar could not be read: " + e.getMessage(), "Re-upload the jar."));
            return null;
        }
        return names;
    }

    private static long discard(InputStream in, long limit) throws IOException {
        OutputStream sink = OutputStream.nullOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            sink.write(buf, 0, n);
            total += n;
            if (total > limit) {
                return total;
            }
        }
        return total;
    }

    private static boolean isSafeName(String name) {
        return !name.isEmpty()
                && !name.startsWith("/")
                && !name.contains("\\")
                && !name.equals("..")
                && !name.startsWith("../")
                && !name.contains("/../")
                && !name.endsWith("/..");
    }

    private void checkManifest(JarFile jar, List<Violation> violations) throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) {
            return;
        }
        var attrs = manifest.getMainAttributes();
        for (String denied : DENIED_MANIFEST_ATTRIBUTES) {
            if (attrs.getValue(denied) != null) {
                violations.add(new Violation(
                        "manifest-attribute-denied",
                        "The manifest sets %s, which a plugin may not use.".formatted(denied),
                        "Remove the %s entry from the manifest.".formatted(denied)));
            }
        }
    }

    private PluginDescriptor readDescriptor(JarFile jar, List<Violation> violations) throws IOException {
        JarEntry entry = jar.getJarEntry("META-INF/artemis-studio/plugin.json");
        if (entry == null) {
            violations.add(new Violation(
                    "descriptor-missing",
                    "The jar has no META-INF/artemis-studio/plugin.json.",
                    "Add the descriptor the build normally generates."));
            return null;
        }
        if (entry.getSize() > PluginDescriptorParser.MAX_BYTES) {
            violations.add(new Violation(
                    "descriptor-too-large",
                    "plugin.json is over the %d byte limit.".formatted(PluginDescriptorParser.MAX_BYTES),
                    "Trim plugin.json; it should be a few hundred bytes of metadata."));
            return null;
        }
        byte[] bytes;
        try (InputStream in = jar.getInputStream(entry)) {
            bytes = in.readNBytes(PluginDescriptorParser.MAX_BYTES + 1);
        }
        try {
            return descriptorParser.parse(bytes);
        } catch (PluginDescriptorException e) {
            violations.add(new Violation(
                    "descriptor-invalid", e.getMessage(), "Fix plugin.json against the published JSON Schema."));
            return null;
        }
    }

    private void checkAllowlist(List<String> names, PluginDescriptor descriptor, List<Violation> violations) {
        String base = descriptor.basePackage().replace('.', '/') + "/";
        String changelogPrefix = "db/changelog/plugin/" + descriptor.id() + "/";
        for (String name : names) {
            if (name.endsWith("/")) {
                continue;
            }
            boolean allowed = name.equals("META-INF/MANIFEST.MF")
                    || name.startsWith("META-INF/maven/")
                    || name.startsWith("META-INF/LICENSE")
                    || name.startsWith("META-INF/NOTICE")
                    || name.startsWith("META-INF/artemis-studio/")
                    || name.startsWith(changelogPrefix)
                    || name.startsWith(base)
                    || name.equals("module-info.class")
                    || name.matches("META-INF/versions/\\d+/" + Pattern.quote(base) + ".*");
            if (!allowed) {
                violations.add(new Violation(
                        "entry-not-allowlisted",
                        "\"%s\" is outside the plugin allowlist.".formatted(name),
                        "Remove it, or move it under %s, the plugin's changelog directory, or META-INF/artemis-studio/."
                                .formatted(descriptor.basePackage())));
            }
        }
    }

    private void checkDescriptorFields(
            PluginDescriptor descriptor, Set<String> otherBasePackages, List<Violation> violations) {
        String id = descriptor.id();
        if (id == null || !ID_PATTERN.matcher(id).matches() || id.length() > 50) {
            violations.add(
                    new Violation(
                            "id-invalid",
                            "\"%s\" is not a valid plugin id.".formatted(id),
                            "Use at least two kebab-case segments (vendor-name), lowercase letters and digits only, at most 50 characters."));
        } else if (id.startsWith("identity-")) {
            violations.add(new Violation(
                    "id-reserved", "Plugin ids starting with \"identity-\" are reserved.", "Choose a different id."));
        }

        String basePackage = descriptor.basePackage();
        if (basePackage == null || !PACKAGE_PATTERN.matcher(basePackage).matches()) {
            violations.add(new Violation(
                    "base-package-invalid",
                    "\"%s\" is not a valid Java package name.".formatted(basePackage),
                    "Use a lowercase dotted package name for basePackage."));
        } else {
            for (String reserved : RESERVED_PREFIXES) {
                if ((basePackage + ".").startsWith(reserved) || reserved.startsWith(basePackage + ".")) {
                    violations.add(
                            new Violation(
                                    "base-package-overlap",
                                    "basePackage \"%s\" overlaps the reserved prefix \"%s\"."
                                            .formatted(basePackage, reserved),
                                    "Choose a basePackage that is not inside, and does not contain, a Studio, JDK or framework package."));
                }
            }
            for (String other : otherBasePackages) {
                if ((basePackage + ".").startsWith(other + ".")
                        || (other + ".").startsWith(basePackage + ".")
                        || basePackage.equals(other)) {
                    violations.add(new Violation(
                            "base-package-overlap",
                            "basePackage \"%s\" overlaps another installed plugin's basePackage \"%s\"."
                                    .formatted(basePackage, other),
                            "Choose a basePackage no other installed plugin uses."));
                }
            }
        }

        if (descriptor.contract() != Contract.VERSION) {
            violations.add(new Violation(
                    "contract-mismatch",
                    "This plugin was built against contract %d; Studio is at contract %d."
                            .formatted(descriptor.contract(), Contract.VERSION),
                    "Rebuild the plugin against the current artemis-studio API."));
        }

        checkStudioRange(descriptor, violations);
        checkNamespaces(descriptor, violations);
    }

    private void checkStudioRange(PluginDescriptor descriptor, List<Violation> violations) {
        PluginDescriptor.Studio studio = descriptor.studio();
        if (studio == null || CalVer.parse(studio.since()).isEmpty()) {
            violations.add(new Violation(
                    "studio-since-invalid",
                    "studio.since is missing or not a YYYY.MM.PATCH version.",
                    "Set studio.since to the Studio version this plugin was built against."));
            return;
        }
        if (!studioVersion.isKnown()) {
            violations.add(new Violation(
                    "studio-version-unknown",
                    "The running Studio version could not be determined, so the compatibility range was not checked.",
                    "Nothing to fix; this is expected on a SNAPSHOT or IDE build of Studio.",
                    Violation.Severity.WARNING));
            return;
        }
        StudioVersion.Compatibility compat = studioVersion.check(studio.since(), studio.until());
        switch (compat) {
            case TOO_OLD ->
                violations.add(new Violation(
                        "studio-too-old",
                        "This plugin requires Studio %s or later.".formatted(studio.since()),
                        "Upgrade Studio, or lower studio.since if the plugin does not really need it."));
            case TOO_NEW ->
                violations.add(new Violation(
                        "studio-too-new",
                        "This plugin declares studio.until=%s, older than the running Studio."
                                .formatted(studio.until()),
                        "Rebuild the plugin against the current Studio and raise studio.until."));
            case UNKNOWN ->
                violations.add(new Violation(
                        "studio-until-invalid",
                        "studio.until \"%s\" is not a YYYY.MM.PATCH version or a YYYY.MM.* wildcard."
                                .formatted(studio.until()),
                        "Fix studio.until."));
            case COMPATIBLE -> {}
        }
    }

    private void checkNamespaces(PluginDescriptor descriptor, List<Violation> violations) {
        String id = descriptor.id();
        if (id == null) {
            return;
        }
        String permPrefix = id + ":";
        for (var permission : descriptor.permissions()) {
            if (permission.action() == null || !permission.action().startsWith(permPrefix)) {
                violations.add(new Violation(
                        "permission-namespace",
                        "Permission \"%s\" is not namespaced under \"%s\".".formatted(permission.action(), permPrefix),
                        "Prefix every permission action with \"%s\".".formatted(permPrefix)));
            }
        }
        String settingPrefix = id + ".";
        for (String key : descriptor.settingKeys()) {
            if (key == null || !key.startsWith(settingPrefix)) {
                violations.add(new Violation(
                        "setting-namespace",
                        "Setting key \"%s\" is not namespaced under \"%s\".".formatted(key, settingPrefix),
                        "Prefix every setting key with \"%s\".".formatted(settingPrefix)));
            }
        }
        for (String topic : descriptor.streamTopics()) {
            if (topic == null || !(topic.equals(id) || topic.startsWith(id + "."))) {
                violations.add(new Violation(
                        "topic-namespace",
                        "Stream topic \"%s\" is not namespaced under \"%s\".".formatted(topic, id),
                        "Name the topic \"%s\" or \"%s.<name>\".".formatted(id, id)));
            }
        }
        String mcpPrefix = id.replace('-', '_') + "_";
        for (var tool : descriptor.mcpTools()) {
            if (tool.name() == null || !tool.name().startsWith(mcpPrefix)) {
                violations.add(new Violation(
                        "mcp-tool-namespace",
                        "Assistant tool \"%s\" is not namespaced under \"%s\".".formatted(tool.name(), mcpPrefix),
                        "Prefix every tool name with \"%s\".".formatted(mcpPrefix)));
            }
        }
        // The @ConfigurationProperties prefix itself (artemis-studio.plugins.<id>) is checked
        // against bytecode in BytecodeChecks, which needs the class file, not just the descriptor.
    }
}
