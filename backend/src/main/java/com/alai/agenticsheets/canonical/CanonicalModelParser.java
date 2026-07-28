package com.alai.agenticsheets.canonical;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only code in this project that reads a
 * {@code canonical-models/*.yaml} file. See
 * {@code canonical-models/SCHEMA.md} for the format and
 * {@link CanonicalModelRegistry} for how this fits into the
 * parse-once/validated/versioned/fail-safe-reload design.
 *
 * Named types are resolved lazily, directly against the raw parsed YAML
 * map, rather than requiring types to be declared in dependency order --
 * a field can reference a type defined later in the same file. Cycle
 * detection uses an in-progress set threaded through the recursion;
 * recursive types are deliberately unsupported (none of the current
 * models need them, and disallowing them keeps both this parser and any
 * future serializer simpler).
 */
@Component
public class CanonicalModelParser {

    private static final Set<String> PRIMITIVE_NAMES = Set.of("String", "Number", "Date", "Boolean");

    public CanonicalModel parse(Path file) {
        Map<String, Object> doc = loadYamlMap(file);

        String modelId = requireString(doc, "model", file);
        int version = requireInt(doc, "version", file);
        Map<String, Object> typesRaw = requireMap(doc, "types", file);
        String rootName = requireString(doc, "root", file);
        Map<String, Object> targetRaw = requireMap(doc, "target", file);

        TargetConfig target = parseTarget(targetRaw, file);

        Map<String, CanonicalType> resolved = new LinkedHashMap<>();
        Set<String> inProgress = new HashSet<>();
        for (String typeName : typesRaw.keySet()) {
            resolveNamedType(typeName, typesRaw, resolved, inProgress, file);
        }

        CanonicalType root = resolved.get(rootName);
        if (root == null) {
            throw new CanonicalConfigException(
                    "root '" + rootName + "' is not defined under types: in " + file);
        }
        if (!(root instanceof RecordType)) {
            throw new CanonicalConfigException(
                    "root '" + rootName + "' must be a record type (one canonical row), not a sum: " + file);
        }

        return new CanonicalModel(modelId, version, target, root, file);
    }

    // --- Type resolution -----------------------------------------------

    private CanonicalType resolveNamedType(String name, Map<String, Object> typesRaw,
            Map<String, CanonicalType> resolved, Set<String> inProgress, Path file) {
        CanonicalType already = resolved.get(name);
        if (already != null) {
            return already;
        }
        if (PRIMITIVE_NAMES.contains(name)) {
            throw new CanonicalConfigException(
                    "type name '" + name + "' collides with a primitive type name: " + file);
        }
        if (inProgress.contains(name)) {
            throw new CanonicalConfigException(
                    "cyclic type reference involving '" + name + "' in " + file
                            + " -- recursive types are not supported");
        }
        Object rawDef = typesRaw.get(name);
        if (!(rawDef instanceof Map)) {
            throw new CanonicalConfigException(
                    "type '" + name + "' must be a mapping with a 'kind': " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> def = (Map<String, Object>) rawDef;
        String kind = asString(def.get("kind"));
        if (kind == null) {
            throw new CanonicalConfigException("type '" + name + "' is missing 'kind': " + file);
        }

        inProgress.add(name);
        try {
            CanonicalType result = switch (kind) {
                case "record" -> resolveRecord(name, def, typesRaw, resolved, inProgress, file);
                case "sum" -> resolveSum(name, def, typesRaw, resolved, inProgress, file);
                default -> throw new CanonicalConfigException(
                        "type '" + name + "' has unknown kind '" + kind
                                + "' (expected 'record' or 'sum'): " + file);
            };
            resolved.put(name, result);
            return result;
        } finally {
            inProgress.remove(name);
        }
    }

    private RecordType resolveRecord(String name, Map<String, Object> def, Map<String, Object> typesRaw,
            Map<String, CanonicalType> resolved, Set<String> inProgress, Path file) {
        Object fieldsRaw = def.get("fields");
        if (fieldsRaw == null) {
            throw new CanonicalConfigException(
                    "record '" + name + "' is missing 'fields' (use {} for none): " + file);
        }
        if (!(fieldsRaw instanceof Map)) {
            throw new CanonicalConfigException("record '" + name + "'s 'fields' must be a mapping: " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldsMap = (Map<String, Object>) fieldsRaw;

        Map<String, CanonicalType> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fieldsMap.entrySet()) {
            fields.put(entry.getKey(),
                    resolveFieldType(entry.getValue(), typesRaw, resolved, inProgress, file, name, entry.getKey()));
        }
        return new RecordType(name, fields);
    }

    private SumType resolveSum(String name, Map<String, Object> def, Map<String, Object> typesRaw,
            Map<String, CanonicalType> resolved, Set<String> inProgress, Path file) {
        Object variantsRaw = def.get("variants");
        if (!(variantsRaw instanceof Map)) {
            throw new CanonicalConfigException("sum '" + name + "' is missing 'variants': " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> variantsMap = (Map<String, Object>) variantsRaw;
        if (variantsMap.isEmpty()) {
            throw new CanonicalConfigException("sum '" + name + "' declares no variants: " + file);
        }

        Map<String, RecordType> variants = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : variantsMap.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                throw new CanonicalConfigException(
                        "variant '" + entry.getKey() + "' of sum '" + name + "' must be a mapping: " + file);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> variantDef = (Map<String, Object>) entry.getValue();
            String variantKind = asString(variantDef.get("kind"));
            if (!"record".equals(variantKind)) {
                throw new CanonicalConfigException(
                        "variant '" + entry.getKey() + "' of sum '" + name
                                + "' must have kind: record: " + file);
            }
            RecordType variantRecord = resolveRecord(
                    name + "." + entry.getKey(), variantDef, typesRaw, resolved, inProgress, file);
            variants.put(entry.getKey(), variantRecord);
        }
        return new SumType(name, variants);
    }

    private CanonicalType resolveFieldType(Object rawValue, Map<String, Object> typesRaw,
            Map<String, CanonicalType> resolved, Set<String> inProgress, Path file,
            String recordName, String fieldName) {
        if (rawValue instanceof String s) {
            return resolveTypeExpression(s, typesRaw, resolved, inProgress, file, recordName, fieldName);
        }
        if (rawValue instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) rawValue;
            String typeExpr = asString(m.get("type"));
            if (typeExpr == null) {
                throw new CanonicalConfigException(
                        "field '" + recordName + "." + fieldName + "' has no valid 'type': " + file);
            }
            CanonicalType base = resolveTypeExpression(typeExpr, typesRaw, resolved, inProgress, file, recordName, fieldName);
            String format = asString(m.get("format"));
            return applyFormat(base, format, recordName, fieldName, file);
        }
        throw new CanonicalConfigException(
                "field '" + recordName + "." + fieldName + "' has an unrecognized type declaration: " + file);
    }

    private CanonicalType resolveTypeExpression(String expr, Map<String, Object> typesRaw,
            Map<String, CanonicalType> resolved, Set<String> inProgress, Path file,
            String recordName, String fieldName) {
        boolean optional = expr.endsWith("?");
        String bare = optional ? expr.substring(0, expr.length() - 1) : expr;
        CanonicalType base = resolveBareType(bare, typesRaw, resolved, inProgress, file, recordName, fieldName);
        return optional ? new OptionType(base) : base;
    }

    private CanonicalType resolveBareType(String bare, Map<String, Object> typesRaw,
            Map<String, CanonicalType> resolved, Set<String> inProgress, Path file,
            String recordName, String fieldName) {
        return switch (bare) {
            case "String" -> new PrimitiveType(PrimitiveType.Kind.STRING, null);
            case "Number" -> new PrimitiveType(PrimitiveType.Kind.NUMBER, null);
            case "Date" -> new PrimitiveType(PrimitiveType.Kind.DATE, PrimitiveType.DEFAULT_DATE_FORMAT);
            case "Boolean" -> new PrimitiveType(PrimitiveType.Kind.BOOLEAN, null);
            default -> {
                if (!typesRaw.containsKey(bare)) {
                    throw new CanonicalConfigException(
                            "field '" + recordName + "." + fieldName + "' references undefined type '"
                                    + bare + "': " + file);
                }
                yield resolveNamedType(bare, typesRaw, resolved, inProgress, file);
            }
        };
    }

    private CanonicalType applyFormat(CanonicalType base, String format, String recordName, String fieldName, Path file) {
        if (format == null) {
            return base;
        }
        if (base instanceof PrimitiveType p) {
            return new PrimitiveType(p.kind(), format);
        }
        if (base instanceof OptionType o && o.inner() instanceof PrimitiveType p) {
            return new OptionType(new PrimitiveType(p.kind(), format));
        }
        throw new CanonicalConfigException(
                "field '" + recordName + "." + fieldName + "' specifies 'format' on a non-primitive type: " + file);
    }

    // --- target: ---------------------------------------------------------

    private TargetConfig parseTarget(Map<String, Object> raw, Path file) {
        String service = requireString(raw, "service", file);
        String transport = requireString(raw, "transport", file);
        if (!transport.equals("rest") && !transport.equals("mcp")) {
            throw new CanonicalConfigException(
                    "target.transport must be 'rest' or 'mcp', got '" + transport + "': " + file);
        }
        String endpoint = requireString(raw, "endpoint", file);
        String tool = asString(raw.get("tool"));
        if (transport.equals("mcp") && (tool == null || tool.isBlank())) {
            throw new CanonicalConfigException("target.tool is required when transport is 'mcp': " + file);
        }

        Map<String, Object> authRaw = requireMap(raw, "auth", file);
        String authType = requireString(authRaw, "type", file);
        String secretRef = requireString(authRaw, "secretRef", file);

        DeliveryConfig delivery = DeliveryConfig.defaults();
        Object deliveryRaw = raw.get("delivery");
        if (deliveryRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> deliveryMap = (Map<String, Object>) deliveryRaw;
            delivery = parseDelivery(deliveryMap, delivery);
        }

        return new TargetConfig(service, transport, endpoint, tool, authType, secretRef, delivery);
    }

    @SuppressWarnings("unchecked")
    private DeliveryConfig parseDelivery(Map<String, Object> raw, DeliveryConfig defaults) {
        int maxAttempts = raw.containsKey("maxAttempts") ? (Integer) raw.get("maxAttempts") : defaults.maxAttempts();
        String backoff = raw.containsKey("backoff") ? (String) raw.get("backoff") : defaults.backoff();
        int initialDelay = raw.containsKey("initialDelaySeconds")
                ? (Integer) raw.get("initialDelaySeconds") : defaults.initialDelaySeconds();
        int maxDelay = raw.containsKey("maxDelaySeconds")
                ? (Integer) raw.get("maxDelaySeconds") : defaults.maxDelaySeconds();
        List<Integer> retryable = raw.containsKey("retryableStatusCodes")
                ? (List<Integer>) raw.get("retryableStatusCodes") : defaults.retryableStatusCodes();
        List<Integer> terminal = raw.containsKey("terminalStatusCodes")
                ? (List<Integer>) raw.get("terminalStatusCodes") : defaults.terminalStatusCodes();
        return new DeliveryConfig(maxAttempts, backoff, initialDelay, maxDelay, retryable, terminal);
    }

    // --- YAML loading + small helpers ------------------------------------

    private Map<String, Object> loadYamlMap(Path file) {
        // SafeConstructor restricts parsing to plain built-in types (Map,
        // List, String, Integer, ...) -- these files come from various
        // teams, not just this project's own authors, so arbitrary Java
        // object construction from a `!!` tag is a real (if unlikely) risk
        // worth closing off rather than relying on trust.
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object loaded;
        try (InputStream in = Files.newInputStream(file)) {
            loaded = yaml.load(in);
        } catch (Exception e) {
            throw new CanonicalConfigException("unable to read/parse YAML: " + file, e);
        }
        if (loaded == null) {
            throw new CanonicalConfigException("empty config file: " + file);
        }
        if (!(loaded instanceof Map)) {
            throw new CanonicalConfigException("top level of config must be a mapping: " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) loaded;
        return doc;
    }

    private String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private String requireString(Map<String, Object> map, String key, Path file) {
        Object v = map.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new CanonicalConfigException("missing or invalid required '" + key + "': " + file);
        }
        return s;
    }

    private int requireInt(Map<String, Object> map, String key, Path file) {
        Object v = map.get(key);
        if (!(v instanceof Integer i)) {
            throw new CanonicalConfigException("missing or invalid required '" + key + "' (expected an integer): " + file);
        }
        return i;
    }

    private Map<String, Object> requireMap(Map<String, Object> map, String key, Path file) {
        Object v = map.get(key);
        if (!(v instanceof Map)) {
            throw new CanonicalConfigException("missing or invalid required '" + key + "' (expected a mapping): " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) v;
        return result;
    }
}
