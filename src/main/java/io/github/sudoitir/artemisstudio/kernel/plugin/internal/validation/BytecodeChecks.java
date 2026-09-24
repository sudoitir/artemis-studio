package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "no code runs, ever" half of {@link PluginValidator} (design.md §3, R2-M7): every class
 * file under the plugin's {@code basePackage} is read with the JDK's {@code java.lang.classfile}
 * API — never loaded, never linked — and checked for annotations and call sites that could let a
 * plugin escape its sandboxed slice of Studio.
 */
final class BytecodeChecks {

    private static final Set<String> DENIED_ANNOTATIONS = Set.of(
            "Lorg/springframework/scheduling/annotation/Scheduled;",
            "Lorg/springframework/scheduling/annotation/Async;",
            "Lorg/springframework/boot/autoconfigure/EnableAutoConfiguration;",
            "Lorg/springframework/boot/autoconfigure/SpringBootApplication;");
    private static final String COMPONENT_SCAN = "Lorg/springframework/context/annotation/ComponentScan;";
    private static final String IMPORT = "Lorg/springframework/context/annotation/Import;";
    private static final String CONFIGURATION_PROPERTIES =
            "Lorg/springframework/boot/context/properties/ConfigurationProperties;";
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "Lorg/springframework/web/bind/annotation/RequestMapping;",
            "Lorg/springframework/web/bind/annotation/GetMapping;",
            "Lorg/springframework/web/bind/annotation/PostMapping;",
            "Lorg/springframework/web/bind/annotation/PutMapping;",
            "Lorg/springframework/web/bind/annotation/DeleteMapping;",
            "Lorg/springframework/web/bind/annotation/PatchMapping;");

    /**
     * Matches a multi-release override entry (design.md §3's allowlist explicitly permits {@code
     * META-INF/versions/<N>/<basePackage>/**}). Group 1 is the plain-path equivalent, which is what
     * every check below actually reasons about — a versioned override is checked exactly as its
     * base entry is, because the JVM may substitute it in at load time.
     */
    private static final Pattern VERSIONED_ENTRY = Pattern.compile("META-INF/versions/\\d+/(.*)");

    private final PluginDescriptor descriptor;
    private final String basePath;

    BytecodeChecks(PluginDescriptor descriptor) {
        this.descriptor = descriptor;
        this.basePath = descriptor.basePackage().replace('.', '/') + "/";
    }

    void check(JarFile jar, List<String> entryNames, List<Violation> violations) throws IOException {
        String configurationFqcn = descriptor.configuration();
        boolean configurationFound = false;
        for (String name : entryNames) {
            String unversioned = unversion(name);
            if (!unversioned.startsWith(basePath) || !unversioned.endsWith(".class")) {
                continue;
            }
            byte[] bytes;
            try (InputStream in = jar.getInputStream(jar.getJarEntry(name))) {
                bytes = in.readAllBytes();
            }
            ClassModel model = ClassFile.of().parse(bytes);
            String className = model.thisClass().asInternalName().replace('/', '.');
            if (className.equals(configurationFqcn)) {
                configurationFound = true;
                checkComponentScanPresent(model, className, violations);
            }
            // A class-level @RequestMapping is only a prefix; what a handler maps is prefix + method path.
            List<String> classPrefixes = mappingPaths(model).orElse(List.of(""));
            checkAnnotations(model, className, null, violations);
            for (MethodModel method : model.methods()) {
                checkAnnotations(
                        method, className + "#" + method.methodName().stringValue(), classPrefixes, violations);
                method.code().ifPresent(code -> {
                    for (CodeElement element : code) {
                        if (element instanceof InvokeInstruction invoke) {
                            checkInvocation(invoke, className, violations);
                        }
                    }
                });
            }
        }
        checkConfigurationField(configurationFqcn, configurationFound, violations);
    }

    private static String unversion(String name) {
        Matcher m = VERSIONED_ENTRY.matcher(name);
        return m.matches() ? m.group(1) : name;
    }

    private void checkConfigurationField(String configurationFqcn, boolean found, List<Violation> violations) {
        if (configurationFqcn == null || configurationFqcn.isBlank()) {
            violations.add(new Violation(
                    "configuration-missing",
                    "The descriptor's configuration is missing.",
                    "Set configuration to the FQCN of the plugin's root @Configuration class."));
            return;
        }
        String configPath = configurationFqcn.replace('.', '/') + ".class";
        if (!configPath.startsWith(basePath)) {
            violations.add(new Violation(
                    "configuration-outside-base-package",
                    "configuration \"%s\" is outside basePackage (%s)."
                            .formatted(configurationFqcn, descriptor.basePackage()),
                    "Point configuration at a class under %s.".formatted(descriptor.basePackage())));
        } else if (!found) {
            violations.add(new Violation(
                    "configuration-not-found",
                    "configuration names \"%s\", but no such class is in the jar.".formatted(configurationFqcn),
                    "Add the class, or fix configuration to name the class that actually ships."));
        }
    }

    private void checkComponentScanPresent(ClassModel model, String className, List<Violation> violations) {
        var attribute = model.findAttribute(Attributes.runtimeVisibleAnnotations());
        boolean hasComponentScan = attribute.isPresent()
                && attribute.get().annotations().stream()
                        .anyMatch(a -> a.className().stringValue().equals(COMPONENT_SCAN));
        if (!hasComponentScan) {
            violations.add(new Violation(
                    "configuration-missing-component-scan",
                    "%s (the plugin's configuration class) does not carry @ComponentScan.".formatted(className),
                    "Add @ComponentScan to %s so its basePackage beans are actually registered.".formatted(className)));
        }
    }

    /**
     * @param classPrefixes the declaring class's mapping paths, for a method; {@code null} for the
     *     class itself, whose mapping is only checked combined with each handler method's
     */
    private void checkAnnotations(
            AttributedElement element, String where, List<String> classPrefixes, List<Violation> violations) {
        var attribute = element.findAttribute(Attributes.runtimeVisibleAnnotations());
        if (attribute.isEmpty()) {
            return;
        }
        for (Annotation annotation : attribute.get().annotations()) {
            String type = annotation.className().stringValue();
            if (DENIED_ANNOTATIONS.contains(type)) {
                violations.add(new Violation(
                        "bytecode-denied-annotation",
                        "%s uses %s, which a plugin may not use.".formatted(where, describe(type)),
                        "Remove %s; a plugin has no @Scheduled/@Async threads and is always auto-configured by Studio."
                                .formatted(describe(type))));
            } else if (type.equals(COMPONENT_SCAN) || type.equals(IMPORT)) {
                checkScanOrImportTargets(annotation, type, where, violations);
            } else if (type.equals(CONFIGURATION_PROPERTIES)) {
                checkConfigurationPropertiesPrefix(annotation, where, violations);
            } else if (MAPPING_ANNOTATIONS.contains(type) && classPrefixes != null) {
                checkMappingPaths(annotation, where, classPrefixes, violations);
            }
        }
    }

    private void checkScanOrImportTargets(
            Annotation annotation, String type, String where, List<Violation> violations) {
        for (AnnotationElement el : annotation.elements()) {
            String name = el.name().stringValue();
            if (!(name.equals("value") || name.equals("basePackages") || name.equals("basePackageClasses"))) {
                continue;
            }
            for (String target : classOrStringPackages(el.value())) {
                if (!target.equals(descriptor.basePackage()) && !target.startsWith(descriptor.basePackage() + ".")) {
                    violations.add(new Violation(
                            "bytecode-scan-outside-base-package",
                            "%s's %s reaches outside basePackage (%s) into \"%s\"."
                                    .formatted(where, describe(type), descriptor.basePackage(), target),
                            "Point %s at a package under %s only."
                                    .formatted(describe(type), descriptor.basePackage())));
                }
            }
        }
    }

    private static List<String> classOrStringPackages(AnnotationValue value) {
        List<String> packages = new ArrayList<>();
        List<AnnotationValue> values = value instanceof AnnotationValue.OfArray arr ? arr.values() : List.of(value);
        for (AnnotationValue v : values) {
            switch (v) {
                case AnnotationValue.OfString s -> packages.add(s.stringValue());
                case AnnotationValue.OfClass c -> {
                    String internal = c.classSymbol().packageName();
                    packages.add(internal);
                }
                default -> {}
            }
        }
        return packages;
    }

    private void checkConfigurationPropertiesPrefix(Annotation annotation, String where, List<Violation> violations) {
        String expected = "artemis-studio.plugins." + descriptor.id();
        String found = null;
        for (AnnotationElement el : annotation.elements()) {
            String name = el.name().stringValue();
            if ((name.equals("value") || name.equals("prefix")) && el.value() instanceof AnnotationValue.OfString s) {
                found = s.stringValue();
            }
        }
        if (found == null || !(found.equals(expected) || found.startsWith(expected + "."))) {
            violations.add(new Violation(
                    "bytecode-configuration-properties-prefix",
                    "%s's @ConfigurationProperties prefix is \"%s\", expected \"%s\"."
                            .formatted(where, found, expected),
                    "Set the prefix to \"%s\".".formatted(expected)));
        }
    }

    /** The paths of a class's own request-mapping annotation, if it has one. */
    private static Optional<List<String>> mappingPaths(AttributedElement element) {
        return element.findAttribute(Attributes.runtimeVisibleAnnotations())
                .flatMap(attribute -> attribute.annotations().stream()
                        .filter(a -> MAPPING_ANNOTATIONS.contains(a.className().stringValue()))
                        .findFirst()
                        .map(BytecodeChecks::paths));
    }

    /** A mapping annotation's {@code value}/{@code path} strings; none declared maps the empty path. */
    private static List<String> paths(Annotation annotation) {
        List<String> paths = new ArrayList<>();
        for (AnnotationElement el : annotation.elements()) {
            String name = el.name().stringValue();
            if (!(name.equals("value") || name.equals("path"))) {
                continue;
            }
            List<AnnotationValue> values =
                    el.value() instanceof AnnotationValue.OfArray arr ? arr.values() : List.of(el.value());
            for (AnnotationValue v : values) {
                if (v instanceof AnnotationValue.OfString s) {
                    paths.add(s.stringValue());
                }
            }
        }
        return paths.isEmpty() ? List.of("") : paths;
    }

    /** Spring's combination of a class and a method path: {@code /a} + {@code b} is {@code /a/b}. */
    private static String combine(String prefix, String path) {
        if (path.isEmpty()) {
            return prefix.isEmpty() ? "/" : prefix;
        }
        String joined = (prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix)
                + (path.startsWith("/") ? path : "/" + path);
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private void checkMappingPaths(
            Annotation annotation, String where, List<String> classPrefixes, List<Violation> violations) {
        List<String> allowedPrefixes =
                List.of("/api/v1/p/" + descriptor.id(), "/api/v1/clusters/{clusterId}/p/" + descriptor.id());
        for (String prefix : classPrefixes) {
            for (String own : paths(annotation)) {
                String path = combine(prefix, own);
                boolean ok = allowedPrefixes.stream()
                        .anyMatch(allowed -> path.equals(allowed) || path.startsWith(allowed + "/"));
                if (!ok) {
                    violations.add(new Violation(
                            "bytecode-mapping-path",
                            "%s maps \"%s\", outside the plugin's gateway paths.".formatted(where, path),
                            "Every mapped path must start with /api/v1/p/%s or /api/v1/clusters/{clusterId}/p/%s."
                                    .formatted(descriptor.id(), descriptor.id())));
                }
            }
        }
    }

    private void checkInvocation(InvokeInstruction invoke, String className, List<Violation> violations) {
        String owner = invoke.owner().asInternalName();
        String name = invoke.name().stringValue();
        String denial =
                switch (owner) {
                    case "java/lang/System" -> name.equals("exit") ? "System.exit" : null;
                    case "java/lang/Runtime" -> (name.equals("halt") || name.equals("exec")) ? "Runtime." + name : null;
                    case "java/lang/ProcessBuilder" -> name.equals("<init>") ? "new ProcessBuilder(...)" : null;
                    case "java/lang/Thread" ->
                        (name.equals("stop") || name.equals("setContextClassLoader")) ? "Thread." + name : null;
                    case "jakarta/servlet/http/HttpSession" ->
                        name.equals("setAttribute") ? "HttpSession.setAttribute" : null;
                    default -> null;
                };
        if (denial != null) {
            violations.add(new Violation(
                    "bytecode-denied-call",
                    "%s calls %s, which a plugin may not call.".formatted(className, denial),
                    "Remove the call to %s; use the curated @PluginApi surface instead.".formatted(denial)));
        }
    }

    private static String describe(String internalClassDescriptor) {
        String fqcn = internalClassDescriptor
                .substring(1, internalClassDescriptor.length() - 1)
                .replace('/', '.');
        return "@" + fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }
}
