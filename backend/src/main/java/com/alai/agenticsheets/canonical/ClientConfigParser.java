package com.alai.agenticsheets.canonical;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The only code in this project that reads a
  * {@code client-configs/*.yaml} file. See {@link CanonicalModelParser}
  * for the same reasoning applied to the (considerably larger) canonical
  * model format. */
@Component
public class ClientConfigParser {

    public ClientConfig parse(Path file) {
        // Explicit, not the (also currently default, but implicit) true
        // -- an external review of Step 9's design correctly flagged
        // that a duplicate feeds: key (a plausible copy-paste mistake)
        // would otherwise be silently accepted with "last one wins"
        // rather than rejected. Applied here even though the parse
        // logic below would have caught a duplicate *feedType* itself
        // via a LinkedHashMap overwrite either way -- this is about not
        // silently accepting malformed YAML at the loader level at all.
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded;
        try (InputStream in = Files.newInputStream(file)) {
            loaded = yaml.load(in);
        } catch (Exception e) {
            throw new CanonicalConfigException("unable to read/parse YAML: " + file, e);
        }
        if (!(loaded instanceof Map)) {
            throw new CanonicalConfigException("empty or invalid config file: " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) loaded;

        Object clientObj = doc.get("client");
        Object dateFormatObj = doc.get("dateFormat");
        if (!(clientObj instanceof String clientId) || clientId.isBlank()) {
            throw new CanonicalConfigException("missing or invalid required 'client': " + file);
        }
        if (!(dateFormatObj instanceof String dateFormat) || dateFormat.isBlank()) {
            throw new CanonicalConfigException("missing or invalid required 'dateFormat': " + file);
        }

        Map<String, FeedRoute> feeds = parseFeeds(doc.get("feeds"), clientId, file);
        Map<String, ClientModelConventions> conventions = parseConventions(doc.get("conventions"), clientId, file);
        return new ClientConfig(clientId, dateFormat, feeds, conventions);
    }

    /** Optional -- empty for a client that never submits through Step
      * 9's inbox scanner. Referenced modelIds aren't validated here;
      * {@code CanonicalModelRegistry} does that once, at reload, against
      * whichever model snapshot is actually current -- this parser has
      * no view of that. */
    private Map<String, FeedRoute> parseFeeds(Object feedsObj, String clientId, Path file) {
        if (feedsObj == null) {
            return Map.of();
        }
        if (!(feedsObj instanceof Map)) {
            throw new CanonicalConfigException("'feeds' must be a map of feedType -> route: " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> feedsMap = (Map<String, Object>) feedsObj;

        Map<String, FeedRoute> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : feedsMap.entrySet()) {
            String feedType = entry.getKey();
            if (feedType == null || feedType.isBlank()) {
                throw new CanonicalConfigException(
                        "client '" + clientId + "' has a blank feed type key: " + file);
            }
            if (!(entry.getValue() instanceof Map)) {
                throw new CanonicalConfigException(
                        "client '" + clientId + "' feed '" + feedType + "' must be a map: " + file);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> routeMap = (Map<String, Object>) entry.getValue();

            Object modelIdObj = routeMap.get("modelId");
            if (!(modelIdObj instanceof String modelId) || modelId.isBlank()) {
                throw new CanonicalConfigException(
                        "client '" + clientId + "' feed '" + feedType + "' missing or invalid 'modelId': " + file);
            }

            Object worksheetNamesObj = routeMap.get("worksheetNames");
            if (!(worksheetNamesObj instanceof List<?> rawList) || rawList.isEmpty()) {
                throw new CanonicalConfigException(
                        "client '" + clientId + "' feed '" + feedType
                                + "' missing or empty 'worksheetNames': " + file);
            }
            List<String> worksheetNames = rawList.stream()
                    .map(item -> {
                        if (!(item instanceof String s) || s.isBlank()) {
                            throw new CanonicalConfigException(
                                    "client '" + clientId + "' feed '" + feedType
                                            + "' has a blank worksheetNames entry: " + file);
                        }
                        return s;
                    })
                    .toList();

            result.put(feedType, new FeedRoute(feedType, modelId, worksheetNames));
        }
        return Map.copyOf(result);
    }

    /** Optional -- empty for a client with no configured conventions.
      * Purely structural here (right shapes, non-blank keys/values, no
      * duplicate YAML keys -- caught for free by {@code SafeConstructor}'s
      * {@code allowDuplicateKeys(false)} above); semantic validation
      * against the actual referenced canonical model (real field paths,
      * real variant names, no ambiguous aliases) happens in
      * {@link CanonicalModelRegistry#reloadClients}, via
      * {@link ClientConventionsValidator} -- this parser has no view of
      * canonical models, same reasoning as {@link #parseFeeds}. */
    private Map<String, ClientModelConventions> parseConventions(Object conventionsObj, String clientId, Path file) {
        if (conventionsObj == null) {
            return Map.of();
        }
        if (!(conventionsObj instanceof Map)) {
            throw new CanonicalConfigException("'conventions' must be a map of modelId -> conventions: " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> conventionsMap = (Map<String, Object>) conventionsObj;

        Map<String, ClientModelConventions> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : conventionsMap.entrySet()) {
            String modelId = entry.getKey();
            if (modelId == null || modelId.isBlank()) {
                throw new CanonicalConfigException(
                        "client '" + clientId + "' has a blank conventions model key: " + file);
            }
            if (!(entry.getValue() instanceof Map)) {
                throw new CanonicalConfigException(
                        "client '" + clientId + "' conventions for '" + modelId + "' must be a map: " + file);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> modelConventions = (Map<String, Object>) entry.getValue();

            Map<String, List<String>> fieldAliases =
                    parseFieldAliases(modelConventions.get("fieldAliases"), clientId, modelId, file);
            Map<String, Map<String, String>> variantValues =
                    parseVariantValues(modelConventions.get("variantValues"), clientId, modelId, file);
            List<String> notProvidedFields =
                    parseNotProvidedFields(modelConventions.get("notProvidedFields"), clientId, modelId, file);

            result.put(modelId, new ClientModelConventions(fieldAliases, variantValues, notProvidedFields));
        }
        return Map.copyOf(result);
    }

    private Map<String, List<String>> parseFieldAliases(Object obj, String clientId, String modelId, Path file) {
        if (obj == null) {
            return Map.of();
        }
        if (!(obj instanceof Map)) {
            throw new CanonicalConfigException(
                    "client '" + clientId + "' model '" + modelId + "' fieldAliases must be a map: " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) obj;

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String fieldPath = entry.getKey();
            if (fieldPath == null || fieldPath.isBlank()) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' has a blank fieldAliases key: " + file);
            }
            if (!(entry.getValue() instanceof List<?> rawList) || rawList.isEmpty()) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' fieldAliases '" + fieldPath + "' must be a non-empty list: " + file);
            }
            List<String> aliases = rawList.stream()
                    .map(item -> {
                        if (!(item instanceof String s) || s.isBlank()) {
                            throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                                    + "' fieldAliases '" + fieldPath + "' has a blank entry: " + file);
                        }
                        return s;
                    })
                    .toList();
            result.put(fieldPath, aliases);
        }
        return Map.copyOf(result);
    }

    private Map<String, Map<String, String>> parseVariantValues(Object obj, String clientId, String modelId, Path file) {
        if (obj == null) {
            return Map.of();
        }
        if (!(obj instanceof Map)) {
            throw new CanonicalConfigException(
                    "client '" + clientId + "' model '" + modelId + "' variantValues must be a map: " + file);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) obj;

        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String fieldPath = entry.getKey();
            if (fieldPath == null || fieldPath.isBlank()) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' has a blank variantValues key: " + file);
            }
            if (!(entry.getValue() instanceof Map)) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' variantValues '" + fieldPath + "' must be a map: " + file);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> valueMap = (Map<String, Object>) entry.getValue();
            if (valueMap.isEmpty()) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' variantValues '" + fieldPath + "' must not be empty: " + file);
            }

            Map<String, String> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> valueEntry : valueMap.entrySet()) {
                String sourceValue = valueEntry.getKey();
                if (sourceValue == null || sourceValue.isBlank()) {
                    throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                            + "' variantValues '" + fieldPath + "' has a blank source value key: " + file);
                }
                if (!(valueEntry.getValue() instanceof String variant) || variant.isBlank()) {
                    throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                            + "' variantValues '" + fieldPath + "' -> '" + sourceValue
                            + "' has a blank or non-string target variant: " + file);
                }
                parsed.put(sourceValue, variant);
            }
            result.put(fieldPath, Map.copyOf(parsed));
        }
        return Map.copyOf(result);
    }

    /** Optional -- empty for a client with no known-absent fields for
      * this model. Purely structural here (a non-empty list of
      * non-blank, non-duplicate path strings), same reasoning as every
      * other parse method in this file -- semantic validation (that
      * each path is actually real AND genuinely optional in the
      * referenced canonical model) happens in
      * {@link ClientConventionsValidator}, which has a view of the
      * canonical model this parser doesn't. Duplicate entries ARE
      * rejected here -- an external review correctly pointed out that
      * silently tolerating a repeated path is a real, if minor, sign of
      * a copy-paste mistake worth catching, the same "don't silently
      * accept malformed config" discipline this parser already applies
      * to duplicate YAML keys via {@code allowDuplicateKeys(false)}. */
    private List<String> parseNotProvidedFields(Object obj, String clientId, String modelId, Path file) {
        if (obj == null) {
            return List.of();
        }
        if (!(obj instanceof List<?> rawList) || rawList.isEmpty()) {
            throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                    + "' notProvidedFields must be a non-empty list: " + file);
        }
        List<String> result = rawList.stream()
                .map(item -> {
                    if (!(item instanceof String s) || s.isBlank()) {
                        throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                                + "' notProvidedFields has a blank entry: " + file);
                    }
                    return s;
                })
                .toList();
        Set<String> seen = new LinkedHashSet<>();
        for (String path : result) {
            if (!seen.add(path)) {
                throw new CanonicalConfigException("client '" + clientId + "' model '" + modelId
                        + "' notProvidedFields has a duplicate entry: '" + path + "': " + file);
            }
        }
        return result;
    }
}
