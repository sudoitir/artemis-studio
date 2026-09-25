package io.github.sudoitir.artemisstudio.kernel.plugin.support;

import io.github.sudoitir.artemisstudio.kernel.plugin.Contract;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Builds a plugin jar in-test: given Java source, {@code plugin.json}, a changelog and any raw
 * entries, it compiles the sources against the test classpath (so a fixture class can freely use
 * Spring, Jakarta and the {@code artemis-studio} API types) and writes a jar to a temp directory.
 * Reused by every {@code kernel.plugin} test — validator, runtime and admin API alike — so a
 * fixture only ever has to say what makes it different from a minimal, otherwise-valid plugin.
 *
 * <p>Nothing here ever loads the compiled classes; the jar is meant to be fed to {@code JarFile}
 * and {@code java.lang.classfile}, never to a classloader in the test JVM itself.
 */
public final class PluginJarBuilder {

    /** A minimal, otherwise-valid {@code plugin.json}, overridable field by field with {@link #descriptorField}. */
    public static Map<String, Object> defaultDescriptor(String id) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("schemaVersion", 1);
        descriptor.put("id", id);
        descriptor.put("name", id);
        descriptor.put("version", "1.0.0");
        descriptor.put("vendor", Map.of("name", "Acme"));
        descriptor.put("basePackage", "com.acme." + id.replace('-', '_'));
        descriptor.put("configuration", "com.acme." + id.replace('-', '_') + ".PluginConfig");
        descriptor.put("contract", Contract.VERSION);
        descriptor.put("studio", Map.of("since", "2026.01.0"));
        descriptor.put("ui", false);
        descriptor.put("activation", "AUTO");
        descriptor.put("title", id);
        return descriptor;
    }

    private final Map<String, Object> descriptor;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, byte[]> rawEntries = new LinkedHashMap<>();
    private final Map<String, String> manifestAttributes = new LinkedHashMap<>();
    private boolean synthesizeConfiguration = true;

    public PluginJarBuilder(String id) {
        this.descriptor = new LinkedHashMap<>(defaultDescriptor(id));
    }

    /** Opts out of the default {@code @Configuration @ComponentScan} class {@link #build()} otherwise synthesizes. */
    public PluginJarBuilder withoutDefaultConfiguration() {
        this.synthesizeConfiguration = false;
        return this;
    }

    public PluginJarBuilder descriptorField(String name, Object value) {
        descriptor.put(name, value);
        return this;
    }

    public PluginJarBuilder removeDescriptorField(String name) {
        descriptor.remove(name);
        return this;
    }

    /** {@code fqcn} must be under this builder's current {@code basePackage} to pass confinement. */
    public PluginJarBuilder source(String fqcn, String javaSource) {
        sources.put(fqcn, javaSource);
        return this;
    }

    public PluginJarBuilder entry(String path, byte[] content) {
        rawEntries.put(path, content);
        return this;
    }

    public PluginJarBuilder entry(String path, String content) {
        return entry(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public PluginJarBuilder manifestAttribute(String name, String value) {
        manifestAttributes.put(name, value);
        return this;
    }

    public PluginJarBuilder changelog(String xml) {
        String id = (String) descriptor.get("id");
        return entry("db/changelog/plugin/" + id + "/changelog.xml", xml);
    }

    public String basePackage() {
        return (String) descriptor.get("basePackage");
    }

    public String id() {
        return (String) descriptor.get("id");
    }

    /**
     * Compiles every added source and writes the jar to a fresh temp file. If nothing was {@link
     * #source(String, String) added} for the descriptor's own {@code configuration} FQCN, a
     * minimal {@code @Configuration @ComponentScan} class is synthesized for it — every fixture
     * gets a plugin that passes the configuration checks unless it deliberately overrides that
     * class to break one.
     */
    public Path build() throws IOException {
        String configurationFqcn = (String) descriptor.get("configuration");
        if (synthesizeConfiguration && configurationFqcn != null && !sources.containsKey(configurationFqcn)) {
            sources.put(configurationFqcn, defaultConfigurationSource(configurationFqcn));
        }
        Path dir = Files.createTempDirectory("plugin-jar-builder");
        Map<String, byte[]> compiled = sources.isEmpty() ? Map.of() : compile();

        Path jarFile = Files.createTempFile(dir, "plugin", ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifestAttributes.forEach((k, v) -> manifest.getMainAttributes().putValue(k, v));

        try (var out = Files.newOutputStream(jarFile);
                JarOutputStream jar = new JarOutputStream(out, manifest)) {
            java.util.Set<String> dirsWritten = new java.util.LinkedHashSet<>();
            writeEntryWithDirs(
                    jar,
                    "META-INF/artemis-studio/plugin.json",
                    toJson(descriptor).getBytes(StandardCharsets.UTF_8),
                    dirsWritten);
            for (var e : rawEntries.entrySet()) {
                writeEntryWithDirs(jar, e.getKey(), e.getValue(), dirsWritten);
            }
            for (var e : compiled.entrySet()) {
                writeEntryWithDirs(jar, e.getKey(), e.getValue(), dirsWritten);
            }
        }
        return jarFile;
    }

    /**
     * Writes {@code name}'s parent directory entries first (once each), then the entry itself. A
     * jar with only file entries has no {@code "com/"}, {@code "com/acme/"}, ... directory
     * entries, so {@code URLClassLoader.getResources("com/acme/notes")} — what Spring's classpath
     * component scan and JPA entity scan both call to find a package inside a jar — resolves
     * against nothing and silently finds no classes, even though {@code Class.forName} on a fully
     * qualified name still works (that path never lists a directory).
     */
    private static void writeEntryWithDirs(
            JarOutputStream jar, String name, byte[] content, java.util.Set<String> dirsWritten) throws IOException {
        int slash = name.lastIndexOf('/');
        if (slash > 0) {
            String dir = name.substring(0, slash + 1);
            for (int i = dir.indexOf('/') + 1; i > 0; i = dir.indexOf('/', i) + 1) {
                String prefix = dir.substring(0, i);
                if (dirsWritten.add(prefix)) {
                    jar.putNextEntry(new JarEntry(prefix));
                    jar.closeEntry();
                }
            }
        }
        writeEntry(jar, name, content);
    }

    private static String defaultConfigurationSource(String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        String pkg = fqcn.substring(0, lastDot);
        String simpleName = fqcn.substring(lastDot + 1);
        return """
                package %s;
                import org.springframework.context.annotation.ComponentScan;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                @ComponentScan
                public class %s {}
                """.formatted(pkg, simpleName);
    }

    private static void writeEntry(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }

    private Map<String, byte[]> compile() throws IOException {
        return compileSources(sources);
    }

    /**
     * Compiles a single class and returns its bytecode — for a test that plants a class file at a
     * hand-chosen entry path (e.g. a {@code META-INF/versions/<N>/...} multi-release override)
     * rather than at the path {@link #source(String, String)} would normally write it to.
     */
    public static byte[] compileOne(String fqcn, String javaSource) throws IOException {
        String path = fqcn.replace('.', '/') + ".class";
        return compileSources(Map.of(fqcn, javaSource)).get(path);
    }

    private static Map<String, byte[]> compileSources(Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available — run tests on a JDK, not a JRE.");
        }
        Path outputDir = Files.createTempDirectory("plugin-jar-builder-classes");
        List<JavaFileObject> units = new ArrayList<>();
        for (var e : sources.entrySet()) {
            units.add(new StringSource(e.getKey(), e.getValue()));
        }
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
            List<String> options =
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:none", "-parameters");
            boolean ok = compiler.getTask(null, fileManager, null, options, null, units)
                    .call();
            if (!ok) {
                throw new IllegalStateException(
                        "PluginJarBuilder fixture source failed to compile: " + sources.keySet());
            }
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (var files = Files.walk(outputDir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String relative = outputDir.relativize(path).toString().replace('\\', '/');
                classes.put(relative, Files.readAllBytes(path));
            }
        }
        return classes;
    }

    /** A minimal, dependency-free JSON writer: descriptors here are plain maps/lists/scalars. */
    private static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case String s ->
                sb.append('"')
                        .append(s.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (var e : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeJson(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    writeJson(e.getValue(), sb);
                }
                sb.append('}');
            }
            case List<?> list -> {
                sb.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeJson(item, sb);
                }
                sb.append(']');
            }
            default ->
                throw new IllegalArgumentException("Cannot render " + value.getClass() + " as JSON in a test fixture.");
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String fqcn, String code) {
            super(URI.create("string:///" + fqcn.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
