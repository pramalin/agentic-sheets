package com.alai.agenticsheets.canonical;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return new ClientConfig(clientId, dateFormat, feeds);
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
}
