package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.TransformerDecl;
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
 * renders a document as the sections an operator pastes inside {@code <core>}.
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

    private static final String QUEUE = "queue";
    private static final String PERMISSION = "permission";
    private static final String DIVERTS = "diverts";
    private static final String SECURITY_SETTINGS = "security-settings";
    private static final String ADDRESSES = "addresses";
    private static final String ADDRESS_SETTINGS = "address-settings";
    private static final String BRIDGES = "bridges";
    private static final String FILTER = "filter";
    private static final String DIVERT = "divert";
    private static final String BRIDGE = "bridge";
    private static final String STRING = "string";
    private static final String AT_NAME = "@name";
    private static final String AT_STRING = "@string";
    private static final String NOT_A_NUMBER = "' is not a number.";
    private static final String FALSE = "false";
    private static final String FORWARDING_ADDRESS = "forwarding-address";
    private static final String ROUTING_TYPE = "routing-type";
    private static final String TRANSFORMER = "transformer";
    private static final String STATIC_CONNECTORS = "static-connectors";
    private static final String DISCOVERY_GROUP_NAME = "discovery-group-name";
    private static final String MAX_CONSUMERS = "max-consumers";
    private static final String EXCLUSIVE = "exclusive";
    private static final String RING_SIZE = "ring-size";
    private static final String NON_DESTRUCTIVE = "non-destructive";
    private static final String DURABLE = "durable";
    private static final String PURGE_ON_NO_CONSUMERS = "purge-on-no-consumers";
    private static final String ADDRESS = "address";
    private static final String ADDRESS_SETTING = "address-setting";
    private static final String SECURITY_SETTING = "security-setting";
    private static final String MATCH = "match";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");
    private static final Set<String> SECTIONS =
            Set.of(ADDRESSES, ADDRESS_SETTINGS, SECURITY_SETTINGS, DIVERTS, BRIDGES);
    private static final Set<String> QUEUE_CHILDREN =
            Set.of(FILTER, DURABLE, MAX_CONSUMERS, PURGE_ON_NO_CONSUMERS, EXCLUSIVE, NON_DESTRUCTIVE, RING_SIZE);

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

    /** The XML declaration, wherever the prolog puts it — after a comment, a BOM or whitespace. */
    private static final Pattern XML_DECLARATION = Pattern.compile("<\\?xml\\s[^?]*\\?>");

    /**
     * A DOCTYPE, with any internal subset. It is dropped, never resolved: DTDs are off,
     * and inside the wrapper below a DOCTYPE would make the whole file unreadable.
     */
    private static final Pattern DOCTYPE =
            Pattern.compile("(?s)<!DOCTYPE[^\\[>]*+(\\[(?:[^\\]]++|](?!\\s*>))*+])?+\\s*>");

    /**
     * A fragment may have several top-level elements; wrap it so the reader sees one
     * document. The prolog of a file off a disk — BOM, declaration, DOCTYPE — cannot sit
     * inside that wrapper, so it goes first.
     */
    private static String wrap(String xml) {
        String body = xml.strip();
        if (body.startsWith("\uFEFF")) {
            body = body.substring(1);
        }
        body = XML_DECLARATION.matcher(body).replaceFirst("");
        body = DOCTYPE.matcher(body).replaceFirst("");
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
        final List<BridgeDecl> bridges = new ArrayList<>();
        final List<Unsupported> unsupported = new ArrayList<>();
        final List<Violation> errors = new ArrayList<>();
        private XMLStreamReader r;

        BrokerConfigDocument document() {
            return new BrokerConfigDocument(
                    BrokerConfigDocument.CURRENT_VERSION,
                    addresses,
                    addressSettings,
                    securitySettings,
                    diverts,
                    bridges);
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
                    case ADDRESSES -> addresses(here);
                    case ADDRESS_SETTINGS -> addressSettings(here);
                    case SECURITY_SETTINGS -> securitySettings(here);
                    case DIVERTS -> diverts(here);
                    case BRIDGES -> bridges(here);
                    // Bare items, as a pasted fragment or a capability snippet writes them.
                    case ADDRESS -> address(here);
                    case ADDRESS_SETTING -> addressSetting(here);
                    case SECURITY_SETTING -> securitySetting(here);
                    case DIVERT -> divert(here);
                    case BRIDGE -> bridge(here);
                    default -> {
                        unsupported.add(new Unsupported(here, notTaken(name)));
                        skip();
                    }
                }
            }
        }

        /**
         * Why a top-level element was not taken. One that belongs inside something this
         * parser does take is told what to wrap it in; only what is truly static is called
         * out of the management API's reach.
         */
        private static String notTaken(String name) {
            if (SECTIONS.contains(name)) {
                return "Duplicate section.";
            }
            if (name.equals(QUEUE) || name.equals("queues")) {
                return "A queue belongs inside an address and its routing type: wrap it in"
                        + " <address name=\"…\"><anycast>…</anycast></address>.";
            }
            if (name.equals("anycast") || name.equals("multicast")) {
                return "A routing type belongs inside an address: wrap it in <address name=\"…\">…</address>.";
            }
            if (name.equals(PERMISSION)) {
                return "A permission belongs inside a security setting: wrap it in"
                        + " <security-setting match=\"…\">…</security-setting>.";
            }
            if (AddressSettingKey.byXmlName(name).isPresent()) {
                return "An address-setting key: wrap it in <address-setting match=\"…\">…</address-setting>.";
            }
            return "Not applied: the management API cannot set this at runtime.";
        }

        /** A path to a named item: {@code addresses/address[name=orders]}. */
        private static String named(String path, String name) {
            return path + "[name=" + name + "]";
        }

        // ---- <addresses> ---------------------------------------------------

        private void addresses(String path) throws XMLStreamException {
            while (nextStartOrEnd()) {
                String here = path + "/" + local();
                if (!local().equals(ADDRESS)) {
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
            String ap = named(here, name);
            Set<String> routingTypes = new LinkedHashSet<>();
            List<QueueDecl> queues = new ArrayList<>();
            while (nextStartOrEnd()) {
                String rt = local();
                if (rt.equals("anycast") || rt.equals("multicast")) {
                    String routingType = rt.toUpperCase(Locale.ROOT);
                    routingTypes.add(routingType);
                    while (nextStartOrEnd()) {
                        if (local().equals(QUEUE)) {
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
            placeholder(name, ap + AT_NAME);
            addresses.add(new AddressDecl(name, routingTypes, queues));
        }

        private QueueDecl queue(String path, String routingType) throws XMLStreamException {
            String name = attr("name");
            String qp = named(path, name);
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
                } else if (child.equals(FILTER)) {
                    filter = attr(STRING);
                    placeholder(filter, cp + AT_STRING);
                    skip();
                } else {
                    String text = text();
                    placeholder(text, cp);
                    try {
                        switch (child) {
                            case DURABLE -> durable = Boolean.parseBoolean(text);
                            case MAX_CONSUMERS -> maxConsumers = Integer.parseInt(text.trim());
                            case PURGE_ON_NO_CONSUMERS -> purge = Boolean.parseBoolean(text);
                            case EXCLUSIVE -> exclusive = Boolean.parseBoolean(text);
                            case NON_DESTRUCTIVE -> nonDestructive = Boolean.parseBoolean(text);
                            case RING_SIZE -> ringSize = Long.parseLong(text.trim());
                            default -> {
                                // Other children carry no queue setting.
                            }
                        }
                    } catch (NumberFormatException _) {
                        errors.add(new Violation(cp, "'" + text + NOT_A_NUMBER));
                    }
                }
            }
            return new QueueDecl(
                    name, routingType, filter, durable, maxConsumers, purge, exclusive, nonDestructive, ringSize);
        }

        // ---- <address-settings> ---------------------------------------------

        private void addressSettings(String path) throws XMLStreamException {
            while (nextStartOrEnd()) {
                String here = path + "/" + local();
                if (!local().equals(ADDRESS_SETTING)) {
                    unsupported.add(new Unsupported(here, "Only <address-setting> is expected here."));
                    skip();
                    continue;
                }
                addressSetting(here);
            }
        }

        /** One <address-setting>, with the reader on its start element; consumed through its end. */
        private void addressSetting(String here) throws XMLStreamException {
            String match = attr(MATCH);
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
                } else {
                    settingValue(key, cp, values);
                }
            }
            addressSettings.add(new AddressSettingDecl(match, values));
        }

        /** The text of one known address-setting key, taken into {@code values} when it can be applied. */
        private void settingValue(AddressSettingKey key, String cp, Map<String, Object> values)
                throws XMLStreamException {
            String text = text();
            if (placeholder(text, cp)) {
                return;
            }
            if (!key.applicable()) {
                unsupported.add(new Unsupported(cp, "Governs a configuration-file reload; not applied at runtime."));
                return;
            }
            values.put(key.jsonName(), coerce(key, text.trim(), cp));
        }

        private Object coerce(AddressSettingKey key, String text, String path) {
            try {
                return switch (key.type()) {
                    case BOOLEAN -> {
                        if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase(FALSE)) {
                            errors.add(new Violation(path, "'" + text + "' is not true or false."));
                        }
                        yield Boolean.parseBoolean(text);
                    }
                    case INT, LONG -> Long.parseLong(text);
                    case DOUBLE -> Double.parseDouble(text);
                    case ENUM -> text.toUpperCase(Locale.ROOT);
                    case STRING -> text;
                };
            } catch (NumberFormatException _) {
                errors.add(new Violation(path, "'" + text + NOT_A_NUMBER));
                return text;
            }
        }

        // ---- <security-settings> -------------------------------------------

        private void securitySettings(String path) throws XMLStreamException {
            while (nextStartOrEnd()) {
                String here = path + "/" + local();
                if (!local().equals(SECURITY_SETTING)) {
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
            String match = attr(MATCH);
            String sp = here + "[match=" + match + "]";
            Map<PermissionType, Set<String>> permissions = new EnumMap<>(PermissionType.class);
            while (nextStartOrEnd()) {
                if (!local().equals(PERMISSION)) {
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
                if (!local().equals(DIVERT)) {
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
            String dp = named(here, name);
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
                    case ADDRESS -> address = placeholderChecked(text(), cp);
                    case FORWARDING_ADDRESS -> forwarding = placeholderChecked(text(), cp);
                    case EXCLUSIVE -> exclusive = Boolean.parseBoolean(text().trim());
                    case ROUTING_TYPE -> routingType = text().trim();
                    case "routing-name" -> {
                        String rn = text().trim();
                        if (!rn.isEmpty() && !rn.equals(name)) {
                            unsupported.add(
                                    new Unsupported(
                                            cp,
                                            "Studio names the routing after the divert; a different routing name is not carried."));
                        }
                    }
                    case FILTER -> {
                        filter = placeholderChecked(attr(STRING), cp + AT_STRING);
                        skip();
                    }
                    case TRANSFORMER -> {
                        TransformerDecl t = transformer(cp);
                        transformerClass = t == null ? null : t.className();
                        transformerProps.putAll(t == null ? Map.of() : t.properties());
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

        /** One {@code <transformer>}, with the reader on its start element; shared by diverts and bridges. */
        private TransformerDecl transformer(String cp) throws XMLStreamException {
            String className = null;
            Map<String, String> properties = new LinkedHashMap<>();
            while (nextStartOrEnd()) {
                switch (local()) {
                    case "class-name" -> className = text().trim();
                    case "property" -> {
                        properties.put(attr("key"), attr("value"));
                        skip();
                    }
                    default -> {
                        unsupported.add(new Unsupported(
                                cp + "/" + local(), "Only <class-name> and <property> are expected here."));
                        skip();
                    }
                }
            }
            return TransformerDecl.of(className, properties);
        }

        // ---- <bridges> -------------------------------------------------------

        private void bridges(String path) throws XMLStreamException {
            while (nextStartOrEnd()) {
                String here = path + "/" + local();
                if (!local().equals(BRIDGE)) {
                    unsupported.add(new Unsupported(here, "Only <bridge> is expected here."));
                    skip();
                    continue;
                }
                bridge(here);
            }
        }

        /** One {@code <bridge>}, with the reader on its start element; consumed through its end. */
        private void bridge(String here) throws XMLStreamException {
            String name = attr("name");
            String bp = named(here, name);
            BridgeFields f = new BridgeFields();
            while (nextStartOrEnd()) {
                String child = local();
                String cp = bp + "/" + child;
                switch (child) {
                    case "queue-name" -> f.queueName = placeholderChecked(text(), cp);
                    case FORWARDING_ADDRESS -> f.forwardingAddress = placeholderChecked(text(), cp);
                    case FILTER -> {
                        f.filter = placeholderChecked(attr(STRING), cp + AT_STRING);
                        skip();
                    }
                    case TRANSFORMER -> f.transformer = transformer(cp);
                    case STATIC_CONNECTORS -> {
                        while (nextStartOrEnd()) {
                            if (local().equals("connector-ref")) {
                                f.staticConnectors.add(placeholderChecked(text(), cp + "/connector-ref"));
                            } else {
                                unsupported.add(
                                        new Unsupported(cp + "/" + local(), "Only <connector-ref> is expected here."));
                                skip();
                            }
                        }
                    }
                    case "discovery-group-ref" -> {
                        f.discoveryGroupName = placeholderChecked(attr(DISCOVERY_GROUP_NAME), cp + AT_NAME);
                        skip();
                    }
                    case DISCOVERY_GROUP_NAME -> f.discoveryGroupName = placeholderChecked(text(), cp);
                    case "ha" -> f.ha = bool(cp);
                    case "use-duplicate-detection" -> f.useDuplicateDetection = bool(cp);
                    case "retry-interval" -> f.retryInterval = whole(cp);
                    case "retry-interval-multiplier" -> f.retryIntervalMultiplier = decimal(cp);
                    case "max-retry-interval" -> f.maxRetryInterval = whole(cp);
                    case "initial-connect-attempts" -> f.initialConnectAttempts = count(cp);
                    case "reconnect-attempts" -> f.reconnectAttempts = count(cp);
                    case "confirmation-window-size" -> f.confirmationWindowSize = count(cp);
                    case "producer-window-size" -> f.producerWindowSize = count(cp);
                    case "min-large-message-size" -> f.minLargeMessageSize = count(cp);
                    case "check-period" -> f.checkPeriod = whole(cp);
                    case "connection-ttl" -> f.connectionTtl = whole(cp);
                    case ROUTING_TYPE -> f.routingType = text().trim();
                    case "concurrency" -> f.concurrency = count(cp);
                    case "client-id" -> f.clientId = placeholderChecked(text(), cp);
                    case "user", "password" -> {
                        // ADR-0092: the credential lives in Studio's vault, not in the
                        // declaration. Named rather than dropped, so an import says where
                        // the value it saw went.
                        unsupported.add(new Unsupported(
                                cp,
                                "Not carried: a bridge's credential is held in Studio's vault and referenced by name,"
                                        + " never stored in the declaration. Set it on the bridge after importing."));
                        skip();
                    }
                    default -> {
                        unsupported.add(new Unsupported(cp, "Not a bridge element Studio knows; not carried."));
                        skip();
                    }
                }
            }
            placeholder(name, bp + AT_NAME);
            bridges.add(new BridgeDecl(
                    name,
                    f.queueName,
                    f.forwardingAddress,
                    f.filter,
                    f.transformer,
                    f.staticConnectors,
                    f.discoveryGroupName,
                    f.ha,
                    f.useDuplicateDetection,
                    f.retryInterval,
                    f.retryIntervalMultiplier,
                    f.maxRetryInterval,
                    f.initialConnectAttempts,
                    f.reconnectAttempts,
                    f.confirmationWindowSize,
                    f.producerWindowSize,
                    f.minLargeMessageSize,
                    f.checkPeriod,
                    f.connectionTtl,
                    f.routingType,
                    f.concurrency,
                    f.clientId,
                    null));
        }

        /** A bridge is twenty-three fields; collecting them as locals would not fit one method. */
        private static final class BridgeFields {
            String queueName;
            String forwardingAddress;
            String filter;
            TransformerDecl transformer;
            final List<String> staticConnectors = new ArrayList<>();
            String discoveryGroupName;
            Boolean ha;
            Boolean useDuplicateDetection;
            Long retryInterval;
            Double retryIntervalMultiplier;
            Long maxRetryInterval;
            Integer initialConnectAttempts;
            Integer reconnectAttempts;
            Integer confirmationWindowSize;
            Integer producerWindowSize;
            Integer minLargeMessageSize;
            Long checkPeriod;
            Long connectionTtl;
            String routingType;
            Integer concurrency;
            String clientId;
        }

        private Boolean bool(String cp) throws XMLStreamException {
            String text = text().trim();
            if (placeholder(text, cp)) {
                return null;
            }
            if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase(FALSE)) {
                errors.add(new Violation(cp, "'" + text + "' is not true or false."));
                return null;
            }
            return Boolean.parseBoolean(text);
        }

        private Long whole(String cp) throws XMLStreamException {
            String text = text().trim();
            if (placeholder(text, cp)) {
                return null;
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException _) {
                errors.add(new Violation(cp, "'" + text + NOT_A_NUMBER));
                return null;
            }
        }

        private Integer count(String cp) throws XMLStreamException {
            Long value = whole(cp);
            if (value == null) {
                return null;
            }
            if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                errors.add(new Violation(cp, value + " is outside the broker's 32-bit range."));
                return null;
            }
            return value.intValue();
        }

        private Double decimal(String cp) throws XMLStreamException {
            String text = text().trim();
            if (placeholder(text, cp)) {
                return null;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException _) {
                errors.add(new Violation(cp, "'" + text + NOT_A_NUMBER));
                return null;
            }
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

    /** The declared sections as an escaped fragment to paste inside {@code <core>}. */
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
            if (!doc.bridges().isEmpty()) {
                x.bridges(doc.bridges());
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
            open(ADDRESSES);
            for (AddressDecl a : addresses) {
                open(ADDRESS, "name", a.name());
                for (String rt : new TreeSet<>(a.routingTypes())) {
                    open(rt.toLowerCase(Locale.ROOT));
                    for (QueueDecl q : a.queues()) {
                        if (q.routingType().equalsIgnoreCase(rt)) {
                            queue(q);
                        }
                    }
                    close();
                }
                close();
            }
            close();
        }

        private void queue(QueueDecl q) throws XMLStreamException {
            boolean simple = q.filter() == null
                    && q.maxConsumers() == null
                    && q.purgeOnNoConsumers() == null
                    && q.exclusive() == null
                    && q.nonDestructive() == null
                    && q.ringSize() == null
                    && q.durable();
            if (simple) {
                indent();
                w.writeEmptyElement(QUEUE);
                w.writeAttribute("name", q.name());
                newline();
                return;
            }
            open(QUEUE, "name", q.name());
            if (q.filter() != null) {
                indent();
                w.writeEmptyElement(FILTER);
                w.writeAttribute(STRING, q.filter());
                newline();
            }
            if (Boolean.FALSE.equals(q.durable())) {
                leaf(DURABLE, FALSE);
            }
            leafIf(MAX_CONSUMERS, q.maxConsumers());
            leafIf(PURGE_ON_NO_CONSUMERS, q.purgeOnNoConsumers());
            leafIf(EXCLUSIVE, q.exclusive());
            leafIf(NON_DESTRUCTIVE, q.nonDestructive());
            leafIf(RING_SIZE, q.ringSize());
            close();
        }

        void addressSettings(List<AddressSettingDecl> settings) throws XMLStreamException {
            open(ADDRESS_SETTINGS);
            for (AddressSettingDecl s : settings) {
                open(ADDRESS_SETTING, MATCH, s.match());
                // Catalogue order, so two exports of the same declaration are identical.
                for (AddressSettingKey key : AddressSettingKey.values()) {
                    Object v = s.values().get(key.jsonName());
                    if (v != null) {
                        leaf(key.xmlName(), String.valueOf(Values.normalise(key, v)));
                    }
                }
                close();
            }
            close();
        }

        void securitySettings(List<SecuritySettingDecl> settings) throws XMLStreamException {
            open(SECURITY_SETTINGS);
            for (SecuritySettingDecl s : settings) {
                open(SECURITY_SETTING, MATCH, s.match());
                for (PermissionType t : PermissionType.values()) {
                    Set<String> roles = s.roles(t);
                    if (roles.isEmpty()) {
                        continue;
                    }
                    indent();
                    w.writeEmptyElement(PERMISSION);
                    w.writeAttribute("type", t.xmlName());
                    w.writeAttribute("roles", String.join(",", new TreeSet<>(roles)));
                    newline();
                }
                close();
            }
            close();
        }

        void diverts(List<DivertDecl> diverts) throws XMLStreamException {
            open(DIVERTS);
            for (DivertDecl d : diverts) {
                open(DIVERT, "name", d.name());
                leaf(ADDRESS, d.address());
                leaf(FORWARDING_ADDRESS, d.forwardingAddress());
                if (d.filter() != null) {
                    indent();
                    w.writeEmptyElement(FILTER);
                    w.writeAttribute(STRING, d.filter());
                    newline();
                }
                leaf(EXCLUSIVE, String.valueOf(d.exclusive()));
                if (d.routingType() != null) {
                    leaf(ROUTING_TYPE, d.routingType());
                }
                transformer(d.transformer());
                close();
            }
            close();
        }

        void bridges(List<BridgeDecl> bridges) throws XMLStreamException {
            open(BRIDGES);
            for (BridgeDecl b : bridges) {
                open(BRIDGE, "name", b.name());
                leaf("queue-name", b.queueName());
                leaf(FORWARDING_ADDRESS, b.forwardingAddress());
                if (b.filter() != null) {
                    indent();
                    w.writeEmptyElement(FILTER);
                    w.writeAttribute(STRING, b.filter());
                    newline();
                }
                transformer(b.transformer());
                leafIf("ha", b.ha());
                leafIf("use-duplicate-detection", b.useDuplicateDetection());
                leafIf("retry-interval", b.retryInterval());
                leafIf("retry-interval-multiplier", b.retryIntervalMultiplier());
                leafIf("max-retry-interval", b.maxRetryInterval());
                leafIf("initial-connect-attempts", b.initialConnectAttempts());
                leafIf("reconnect-attempts", b.reconnectAttempts());
                leafIf("confirmation-window-size", b.confirmationWindowSize());
                leafIf("producer-window-size", b.producerWindowSize());
                leafIf("min-large-message-size", b.minLargeMessageSize());
                leafIf("check-period", b.checkPeriod());
                leafIf("connection-ttl", b.connectionTtl());
                leafIf(ROUTING_TYPE, b.routingType());
                leafIf("concurrency", b.concurrency());
                leafIf("client-id", b.clientId());
                credential(b.credentialRef());
                if (!b.staticConnectors().isEmpty()) {
                    open(STATIC_CONNECTORS);
                    for (String c : b.staticConnectors()) {
                        leaf("connector-ref", c);
                    }
                    close();
                } else if (b.discoveryGroupName() != null) {
                    indent();
                    w.writeEmptyElement("discovery-group-ref");
                    w.writeAttribute(DISCOVERY_GROUP_NAME, b.discoveryGroupName());
                    newline();
                }
                close();
            }
            close();
        }

        /**
         * ADR-0092: an exported fragment is a file people paste into repositories, so
         * it names the credential to supply and never carries one. The secret is in
         * Studio's vault and no export path can reach it.
         */
        private void credential(String credentialRef) throws XMLStreamException {
            if (credentialRef == null) {
                return;
            }
            indent();
            w.writeComment(" Credential '" + credentialRef
                    + "' is held in Artemis Studio's vault and is not exported. Supply it here. ");
            newline();
            leaf("user", "${" + credentialRef + ".user}");
            leaf("password", "${" + credentialRef + ".password}");
        }

        private void transformer(BrokerConfigDocument.TransformerDecl t) throws XMLStreamException {
            if (t == null) {
                return;
            }
            open(TRANSFORMER);
            leaf("class-name", t.className());
            for (Map.Entry<String, String> e : t.properties().entrySet()) {
                indent();
                w.writeEmptyElement("property");
                w.writeAttribute("key", e.getKey());
                w.writeAttribute("value", e.getValue());
                newline();
            }
            close();
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

        private void close() throws XMLStreamException {
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
