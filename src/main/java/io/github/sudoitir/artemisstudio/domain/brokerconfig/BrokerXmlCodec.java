package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * {@code broker.xml} in and out (ADR-0067 D1). XML is an interchange format for the
 * declaration, never its editor: {@link #parse} turns a pasted file or fragment into a
 * document and lists — by path — every element it did not take, and {@link #write}
 * renders a document as the four sections an operator pastes inside {@code <core>}.
 *
 * <p>JDK StAX only; no dependency. Namespaces are ignored (local names decide), so a
 * full {@code <configuration xmlns="…"><core>} file, a bare {@code <core>}, and a
 * fragment of sibling sections all parse. Element and attribute names are the ones
 * {@code FileConfigurationParser} reads; values are coerced through the catalogue so
 * a number pasted as text and the same number declared in JSON compare equal.
 */
public final class BrokerXmlCodec {

    /**
     * The largest fragment an import will parse. A real {@code broker.xml} is a few
     * tens of kilobytes; this is a bound on the work one request can ask for, not a
     * judgement about anyone's configuration.
     */
    static final int MAX_IMPORT_CHARS = 256 * 1024;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");
    private static final Set<String> SECTIONS = Set.of("addresses", "address-settings", "security-settings", "diverts");
    private static final Set<String> QUEUE_CHILDREN = Set.of(
            "filter", "durable", "max-consumers", "purge-on-no-consumers", "exclusive", "non-destructive", "ring-size");

    private BrokerXmlCodec() {}

    /** What a parse produced. {@code errors} non-empty means the document must not be saved. */
    public record ParseResult(BrokerConfigDocument document, List<Unsupported> unsupported, List<Violation> errors) {
        public ParseResult {
            unsupported = List.copyOf(unsupported == null ? List.of() : unsupported);
            errors = List.copyOf(errors == null ? List.of() : errors);
        }
    }

    /** An element the parser saw and could not carry into the declaration. */
    public record Unsupported(String path, String reason) {}

    // ---- parse -------------------------------------------------------------

    public static ParseResult parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return new ParseResult(
                    BrokerConfigDocument.empty(), List.of(), List.of(new Violation("", "Nothing to import.")));
        }
        if (xml.length() > MAX_IMPORT_CHARS) {
            // A broker.xml is kilobytes; anything at this scale is a mistake or an attempt
            // to make the parser the expensive part of a request. Refused before parsing.
            return new ParseResult(
                    BrokerConfigDocument.empty(),
                    List.of(),
                    List.of(new Violation(
                            "",
                            "This is " + (xml.length() / 1024) + " KiB; the largest import Studio accepts is "
                                    + (MAX_IMPORT_CHARS / 1024) + " KiB. Paste the <core> fragment you mean to"
                                    + " declare rather than a whole configuration tree.")));
        }
        Parser p = new Parser();
        try {
            p.run(wrap(xml));
        } catch (XMLStreamException e) {
            String where = e.getLocation() == null
                    ? ""
                    : " (line " + e.getLocation().getLineNumber() + ", column "
                            + e.getLocation().getColumnNumber() + ")";
            p.errors.add(new Violation("", "Not well-formed XML" + where + ": " + tidy(e.getMessage())));
        }
        return new ParseResult(p.document(), p.unsupported, p.errors);
    }

    /** A fragment may have several top-level elements; wrap it so the reader sees one document. */
    private static String wrap(String xml) {
        String body = xml.strip();
        if (body.startsWith("<?xml")) {
            body = body.substring(body.indexOf("?>") + 2);
        }
        return "<studio-import>" + body + "</studio-import>";
    }

    private static String tidy(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("\\s+", " ")
                .replaceFirst("^ParseError at \\[row,col\\]:\\[\\d+,\\d+\\]\\s*Message:\\s*", "")
                .trim();
    }

    private static final class Parser {
        final List<AddressDecl> addresses = new ArrayList<>();
        final List<AddressSettingDecl> addressSettings = new ArrayList<>();
        final List<SecuritySettingDecl> securitySettings = new ArrayList<>();
        final List<DivertDecl> diverts = new ArrayList<>();
        final List<Unsupported> unsupported = new ArrayList<>();
        final List<Violation> errors = new ArrayList<>();
        private XMLStreamReader r;

        BrokerConfigDocument document() {
            return new BrokerConfigDocument(
                    BrokerConfigDocument.CURRENT_VERSION, addresses, addressSettings, securitySettings, diverts);
        }

        void run(String xml) throws XMLStreamException {
            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
            r = f.createXMLStreamReader(new StringReader(xml));
            expectStart(); // studio-import
            container("");
        }

        /** Walk the children of a container, descending into {@code configuration} and {@code core}, taking sections, listing the rest. */
        private void container(String path) throws XMLStreamException {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    return;
                }
                if (ev != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String name = local();
                String here = path.isEmpty() ? name : path + "/" + name;
                switch (name) {
                    case "configuration", "core" -> container(here);
                    case "addresses" -> addresses(here);
                    case "address-settings" -> addressSettings(here);
                    case "security-settings" -> securitySettings(here);
                    case "diverts" -> diverts(here);
                    // Bare items, as a pasted fragment or a capability snippet writes them.
                    case "address" -> address(here);
                    case "address-setting" -> addressSetting(here);
                    case "security-setting" -> securitySetting(here);
                    case "divert" -> divert(here);
                    default -> {
                        unsupported.add(new Unsupported(
                                here,
                                SECTIONS.contains(name)
                                        ? "Duplicate section."
                                        : "Not applied: the management API cannot set this at runtime."));
                        skip();
                    }
                }
            }
        }

        // ---- <addresses> ---------------------------------------------------

        private void addresses(String path) throws XMLStreamException {
            while (nextStartOrEnd()) {
                String here = path + "/" + local();
                if (!local().equals("address")) {
                    unsupported.add(new Unsupported(here, "Only <address> is expected here."));
                    skip();
                    continue;
                }
                address(here);
            }
        }

        /** One <address>, with the reader on its start element; consumed through its end. */
        private void address(String here) throws XMLStreamException {
            String name = attr("name");
            String ap = here + "[name=" + name + "]";
            Set<String> routingTypes = new LinkedHashSet<>();
            List<QueueDecl> queues = new ArrayList<>();
            while (nextStartOrEnd()) {
                String rt = local();
                if (rt.equals("anycast") || rt.equals("multicast")) {
                    String routingType = rt.toUpperCase(Locale.ROOT);
                    routingTypes.add(routingType);
                    while (nextStartOrEnd()) {
                        if (local().equals("queue")) {
                            queues.add(queue(ap + "/" + rt + "/queue", routingType));
                        } else {
                            unsupported.add(
                                    new Unsupported(ap + "/" + rt + "/" + local(), "Only <queue> is expected here."));
                            skip();
                        }
                    }
                } else {
                    unsupported.add(
                            new Unsupported(ap + "/" + rt, "Only <anycast> and <multicast> are expected here."));
                    skip();
                }
            }
            placeholder(name, ap + "@name");
            addresses.add(new AddressDecl(name, routingTypes, queues));
        }

        private QueueDecl queue(String path, String routingType) throws XMLStreamException {
            String name = attr("name");
            String qp = path + "[name=" + name + "]";
            String filter = null;
            Boolean durable = null;
            Integer maxConsumers = null;
            Boolean purge = null;
            Boolean exclusive = null;
            Boolean nonDestructive = null;
            Long ringSize = null;
            while (nextStartOrEnd()) {
                String child = local();
                String cp = qp + "/" + child;
                if (!QUEUE_CHILDREN.contains(child)) {
                    unsupported.add(new Unsupported(
                            cp, "Not carried: Studio declares the queue fields its create action accepts."));
                    skip();
                    continue;
                }
                if (child.equals("filter")) {
                    filter = attr("string");
                    placeholder(filter, cp + "@string");
                    skip();
                    continue;
                }
                String text = text();
                placeholder(text, cp);
                try {
                    switch (child) {
                        case "durable" -> durable = Boolean.parseBoolean(text);
                        case "max-consumers" -> maxConsumers = Integer.parseInt(text.trim());
                        case "purge-on-no-consumers" -> purge = Boolean.parseBoolean(text);
                        case "exclusive" -> exclusive = Boolean.parseBoolean(text);
                        case "non-destructive" -> nonDestructive = Boolean.parseBoolean(text);
                        case "ring-size" -> ringSize = Long.parseLong(text.trim());
                        default -> {}
                    }
                } catch (NumberFormatException e) {
                    errors.add(new Violation(cp, "'" + text + "' is not a number."));
                }
            }
            return new QueueDecl(
                    name, routingType, filter, durable, maxConsumers, purge, exclusive, nonDestructive, ringSize);
        }

        // ---- <address-settings> ---------------------------------------------

        private void addressSettings(String path) throws XMLStreamException {
            while (nextStartOrEnd()) {
                String here = path + "/" + local();
                if (!local().equals("address-setting")) {
                    unsupported.add(new Unsupported(here, "Only <address-setting> is expected here."));
                    skip();
                    continue;
                }
                addressSetting(here);
            }
        }

        /** One <address-setting>, with the reader on its start element; consumed through its end. */
        private void addressSetting(String here) throws XMLStreamException {
            String match = attr("match");
            String sp = here + "[match=" + match + "]";
            Map<String, Object> values = new TreeMap<>();
            while (nextStartOrEnd()) {
                String child = local();
                String cp = sp + "/" + child;
                AddressSettingKey key = AddressSettingKey.byXmlName(child).orElse(null);
                if (key == null) {
                    // ADR-0067 D10: refused, not dropped. The broker accepts an unknown key
                    // and does nothing with it, so carrying the import on would declare a
                    // setting that can never be applied and can never drift — and a typo
                    // ('max-size-byte') would look exactly like a success.
                    errors.add(new Violation(
                            cp,
                            "'" + child + "' is not an address-setting key Studio knows. Correct the spelling, or"
                                    + " remove it: a broker accepts an unknown key and silently ignores it."));
                    skip();
                    continue;
                }
                String text = text();
                if (placeholder(text, cp)) {
                    continue;
                }
                if (!key.applicable()) {
                    unsupported.add(
                            new Unsupported(cp, "Governs a configuration-file reload; not applied at runtime."));
                    continue;
                }
                values.put(key.jsonName(), coerce(key, text.trim(), cp));
            }
            addressSettings.add(new AddressSettingDecl(match, values));
        }

        private Object coerce(AddressSettingKey key, String text, String path) {
            try {
                return switch (key.type()) {
                    case BOOLEAN -> {
                        if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                            errors.add(new Violation(path, "'" + text + "' is not true or false."));
                        }
                        yield Boolean.parseBoolean(text);
                    }
                    case INT, LONG -> Long.parseLong(text);
                    case DOUBLE -> Double.parseDouble(text);
                    case ENUM -> text.toUpperCase(Locale.ROOT);
                    case STRING -> text;
                };
            } catch (NumberFormatException e) {
                errors.add(new Violation(path, "'" + text + "' is not a number."));
                return text;
            }
        }

        // ---- <security-settings> -------------------------------------------

        private void securitySettings(String path) throws XMLStreamException {
            while (nextStartOrEnd()) {
                String here = path + "/" + local();
                if (!local().equals("security-setting")) {
                    unsupported.add(new Unsupported(
                            here,
                            "Not carried: only <security-setting> with <permission> children is applied at runtime."));
                    skip();
                    continue;
                }
                securitySetting(here);
            }
        }

        /** One <security-setting>, with the reader on its start element; consumed through its end. */
        private void securitySetting(String here) throws XMLStreamException {
            String match = attr("match");
            String sp = here + "[match=" + match + "]";
            Map<PermissionType, Set<String>> permissions = new EnumMap<>(PermissionType.class);
            while (nextStartOrEnd()) {
                if (!local().equals("permission")) {
                    unsupported.add(new Unsupported(sp + "/" + local(), "Only <permission> is expected here."));
                    skip();
                    continue;
                }
                String type = attr("type");
                String roles = attr("roles");
                PermissionType pt = PermissionType.byXmlName(type).orElse(null);
                if (pt == null) {
                    unsupported.add(new Unsupported(
                            sp + "/permission[type=" + type + "]", "Not a permission type Studio knows."));
                } else if (roles != null && !placeholder(roles, sp + "/permission[type=" + type + "]@roles")) {
                    Set<String> set = permissions.computeIfAbsent(pt, k -> new TreeSet<>());
                    Arrays.stream(roles.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(set::add);
                }
                skip();
            }
            securitySettings.add(new SecuritySettingDecl(match, permissions));
        }

        // ---- <diverts> -------------------------------------------------------

        private void diverts(String path) throws XMLStreamException {
            while (nextStartOrEnd()) {
                String here = path + "/" + local();
                if (!local().equals("divert")) {
                    unsupported.add(new Unsupported(here, "Only <divert> is expected here."));
                    skip();
                    continue;
                }
                divert(here);
            }
        }

        /** One <divert>, with the reader on its start element; consumed through its end. */
        private void divert(String here) throws XMLStreamException {
            String name = attr("name");
            String dp = here + "[name=" + name + "]";
            String address = null;
            String forwarding = null;
            String filter = null;
            boolean exclusive = false;
            String routingType = null;
            String transformerClass = null;
            Map<String, String> transformerProps = new LinkedHashMap<>();
            while (nextStartOrEnd()) {
                String child = local();
                String cp = dp + "/" + child;
                switch (child) {
                    case "address" -> address = placeholderChecked(text(), cp);
                    case "forwarding-address" -> forwarding = placeholderChecked(text(), cp);
                    case "exclusive" -> exclusive = Boolean.parseBoolean(text().trim());
                    case "routing-type" -> routingType = text().trim();
                    case "routing-name" -> {
                        String rn = text().trim();
                        if (!rn.isEmpty() && !rn.equals(name)) {
                            unsupported.add(
                                    new Unsupported(
                                            cp,
                                            "Studio names the routing after the divert; a different routing name is not carried."));
                        }
                    }
                    case "filter" -> {
                        filter = placeholderChecked(attr("string"), cp + "@string");
                        skip();
                    }
                    case "transformer" -> {
                        while (nextStartOrEnd()) {
                            switch (local()) {
                                case "class-name" -> transformerClass = text().trim();
                                case "property" -> {
                                    transformerProps.put(attr("key"), attr("value"));
                                    skip();
                                }
                                default -> {
                                    unsupported.add(new Unsupported(
                                            cp + "/" + local(), "Only <class-name> and <property> are expected here."));
                                    skip();
                                }
                            }
                        }
                    }
                    default -> {
                        unsupported.add(new Unsupported(cp, "Not a divert element Studio knows; not carried."));
                        skip();
                    }
                }
            }
            diverts.add(new DivertDecl(
                    name, address, forwarding, filter, exclusive, routingType, transformerClass, transformerProps));
        }

        // ---- reader helpers ------------------------------------------------

        private void expectStart() throws XMLStreamException {
            while (r.hasNext() && r.next() != XMLStreamConstants.START_ELEMENT) {
                // skip prolog
            }
        }

        /** Advance to the next child start element; false at the parent's end. */
        private boolean nextStartOrEnd() throws XMLStreamException {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    return true;
                }
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    return false;
                }
            }
            return false;
        }

        private String local() {
            return r.getLocalName();
        }

        private String attr(String name) {
            String v = r.getAttributeValue(null, name);
            return v == null ? null : v.trim();
        }

        /** The text of the current element, consuming its end tag. */
        private String text() throws XMLStreamException {
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (depth == 0) {
                        sb.append(r.getText());
                    }
                } else if (ev == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 0) {
                        return sb.toString();
                    }
                    depth--;
                }
            }
            return sb.toString();
        }

        /** Consume the current element and everything under it. */
        private void skip() throws XMLStreamException {
            int depth = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 0) {
                        return;
                    }
                    depth--;
                }
            }
        }

        private boolean placeholder(String value, String path) {
            if (value != null && PLACEHOLDER.matcher(value).find()) {
                errors.add(new Violation(
                        path,
                        "'" + value.trim()
                                + "' is a placeholder. Studio cannot resolve it; enter the concrete value."));
                return true;
            }
            return false;
        }

        private String placeholderChecked(String value, String path) {
            placeholder(value, path);
            return value == null ? null : value.trim();
        }
    }

    // ---- write -------------------------------------------------------------

    /** The four sections as an escaped fragment to paste inside {@code <core>}. */
    public static String write(BrokerConfigDocument doc) {
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter w = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
            Writer x = new Writer(w);
            x.comment("Generated by Artemis Studio. Paste inside <core> of broker.xml.");
            if (!doc.addresses().isEmpty()) {
                x.addresses(doc.addresses());
            }
            if (!doc.addressSettings().isEmpty()) {
                x.addressSettings(doc.addressSettings());
            }
            if (!doc.securitySettings().isEmpty()) {
                x.securitySettings(doc.securitySettings());
            }
            if (!doc.diverts().isEmpty()) {
                x.diverts(doc.diverts());
            }
            w.flush();
            w.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not render broker.xml fragment", e);
        }
        return out.toString().stripTrailing() + "\n";
    }

    private static final class Writer {
        private final XMLStreamWriter w;
        private int depth;

        Writer(XMLStreamWriter w) {
            this.w = w;
        }

        void comment(String text) throws XMLStreamException {
            w.writeComment(" " + text + " ");
            newline();
        }

        void addresses(List<AddressDecl> addresses) throws XMLStreamException {
            open("addresses");
            for (AddressDecl a : addresses) {
                open("address", "name", a.name());
                for (String rt : new TreeSet<>(a.routingTypes())) {
                    open(rt.toLowerCase(Locale.ROOT));
                    for (QueueDecl q : a.queues()) {
                        if (!q.routingType().equalsIgnoreCase(rt)) {
                            continue;
                        }
                        boolean simple = q.filter() == null
                                && q.maxConsumers() == null
                                && q.purgeOnNoConsumers() == null
                                && q.exclusive() == null
                                && q.nonDestructive() == null
                                && q.ringSize() == null
                                && q.durable();
                        if (simple) {
                            indent();
                            w.writeEmptyElement("queue");
                            w.writeAttribute("name", q.name());
                            newline();
                            continue;
                        }
                        open("queue", "name", q.name());
                        if (q.filter() != null) {
                            indent();
                            w.writeEmptyElement("filter");
                            w.writeAttribute("string", q.filter());
                            newline();
                        }
                        if (!q.durable()) {
                            leaf("durable", "false");
                        }
                        leafIf("max-consumers", q.maxConsumers());
                        leafIf("purge-on-no-consumers", q.purgeOnNoConsumers());
                        leafIf("exclusive", q.exclusive());
                        leafIf("non-destructive", q.nonDestructive());
                        leafIf("ring-size", q.ringSize());
                        close("queue");
                    }
                    close(rt.toLowerCase(Locale.ROOT));
                }
                close("address");
            }
            close("addresses");
        }

        void addressSettings(List<AddressSettingDecl> settings) throws XMLStreamException {
            open("address-settings");
            for (AddressSettingDecl s : settings) {
                open("address-setting", "match", s.match());
                // Catalogue order, so two exports of the same declaration are identical.
                for (AddressSettingKey key : AddressSettingKey.values()) {
                    Object v = s.values().get(key.jsonName());
                    if (v != null) {
                        leaf(key.xmlName(), String.valueOf(Values.normalise(key, v)));
                    }
                }
                close("address-setting");
            }
            close("address-settings");
        }

        void securitySettings(List<SecuritySettingDecl> settings) throws XMLStreamException {
            open("security-settings");
            for (SecuritySettingDecl s : settings) {
                open("security-setting", "match", s.match());
                for (PermissionType t : PermissionType.values()) {
                    Set<String> roles = s.roles(t);
                    if (roles.isEmpty()) {
                        continue;
                    }
                    indent();
                    w.writeEmptyElement("permission");
                    w.writeAttribute("type", t.xmlName());
                    w.writeAttribute("roles", String.join(",", new TreeSet<>(roles)));
                    newline();
                }
                close("security-setting");
            }
            close("security-settings");
        }

        void diverts(List<DivertDecl> diverts) throws XMLStreamException {
            open("diverts");
            for (DivertDecl d : diverts) {
                open("divert", "name", d.name());
                leaf("address", d.address());
                leaf("forwarding-address", d.forwardingAddress());
                if (d.filter() != null) {
                    indent();
                    w.writeEmptyElement("filter");
                    w.writeAttribute("string", d.filter());
                    newline();
                }
                leaf("exclusive", String.valueOf(d.exclusive()));
                if (d.routingType() != null) {
                    leaf("routing-type", d.routingType());
                }
                if (d.transformerClassName() != null) {
                    open("transformer");
                    leaf("class-name", d.transformerClassName());
                    for (Map.Entry<String, String> e : d.transformerProperties().entrySet()) {
                        indent();
                        w.writeEmptyElement("property");
                        w.writeAttribute("key", e.getKey());
                        w.writeAttribute("value", e.getValue());
                        newline();
                    }
                    close("transformer");
                }
                close("divert");
            }
            close("diverts");
        }

        private void open(String name) throws XMLStreamException {
            indent();
            w.writeStartElement(name);
            newline();
            depth++;
        }

        private void open(String name, String attr, String value) throws XMLStreamException {
            indent();
            w.writeStartElement(name);
            w.writeAttribute(attr, value == null ? "" : value);
            newline();
            depth++;
        }

        private void close(String name) throws XMLStreamException {
            depth--;
            indent();
            w.writeEndElement();
            newline();
        }

        private void leaf(String name, String text) throws XMLStreamException {
            indent();
            w.writeStartElement(name);
            w.writeCharacters(text == null ? "" : text);
            w.writeEndElement();
            newline();
        }

        private void leafIf(String name, Object value) throws XMLStreamException {
            if (value != null) {
                leaf(name, String.valueOf(value));
            }
        }

        private void indent() throws XMLStreamException {
            w.writeCharacters("   ".repeat(depth));
        }

        private void newline() throws XMLStreamException {
            w.writeCharacters("\n");
        }
    }
}
