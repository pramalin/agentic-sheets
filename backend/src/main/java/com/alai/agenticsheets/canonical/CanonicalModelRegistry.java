package com.alai.agenticsheets.canonical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * The single owner of {@code canonical-models/*.yaml} and
 * {@code client-configs/*.yaml}. Nothing else in the codebase parses
 * these files -- every consumer (the mapping agent's prompt builder,
 * the deterministic validator, the dispatcher, Step 9's inbox scanner)
 * reads {@link #get(String)} / {@link #getClient(String)} /
 * {@link #resolveRoute}.
 *
 * This exists specifically to avoid a real failure mode from a prior
 * system: configuration that was "not constant at all," ending up
 * treated as a raw string and re-parsed ad hoc in multiple places. See
 * {@code mapping-notes.md} and {@code canonical-models/SCHEMA.md}'s
 * "Loading &amp; reload" section.
 *
 * Reload is per-file, not all-or-nothing across every config: a file
 * that fails to parse or validate -- including, as of Step 9, a
 * client's feed referencing a model id that doesn't exist -- leaves its
 * own previous good config in place (or absent, if it never loaded
 * successfully) and does not affect any other file. A mistake in one
 * team's config can't corrupt another team's pipeline.
 *
 * One {@link RegistrySnapshot}, swapped atomically, not two
 * independently-updated fields -- see that record's own javadoc for the
 * race this closes.
 */
@Component
public class CanonicalModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(CanonicalModelRegistry.class);

    private final Path canonicalModelsDir;
    private final Path clientConfigsDir;
    private final CanonicalModelParser modelParser;
    private final ClientConfigParser clientParser;

    private volatile RegistrySnapshot snapshot = RegistrySnapshot.EMPTY;

    public CanonicalModelRegistry(
            @Value("${agentic-sheets.canonical-models-dir:/config/canonical-models}") String canonicalModelsDir,
            @Value("${agentic-sheets.client-configs-dir:/config/client-configs}") String clientConfigsDir,
            CanonicalModelParser modelParser,
            ClientConfigParser clientParser) {
        this.canonicalModelsDir = Path.of(canonicalModelsDir);
        this.clientConfigsDir = Path.of(clientConfigsDir);
        this.modelParser = modelParser;
        this.clientParser = clientParser;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        reload();
    }

    /** Configuration here is expected to change often -- reload on a
      * schedule rather than only at startup, so an edited YAML file is
      * picked up without a redeploy. */
    @Scheduled(fixedDelayString = "${agentic-sheets.canonical.reload-interval-ms:300000}")
    public void reload() {
        RegistrySnapshot current = this.snapshot;

        Map<String, CanonicalModel> models = reloadModels(current.models());
        // Clients are (re)loaded and route-validated against `models`
        // above -- the map about to become current, not the one this
        // reload cycle started with -- so a client's feeds always
        // validate against whichever model generation will actually be
        // in effect once this reload completes, never a stale one.
        Map<String, ClientConfig> clients = reloadClients(current.clients(), models);
        Map<FeedRouteKey, FeedRoute> routes = buildRouteIndex(clients);

        this.snapshot = new RegistrySnapshot(models, clients, routes);
    }

    private Map<String, CanonicalModel> reloadModels(Map<String, CanonicalModel> previous) {
        Map<String, CanonicalModel> next = new LinkedHashMap<>(previous);
        for (Path file : listYamlFiles(canonicalModelsDir)) {
            try {
                CanonicalModel parsed = modelParser.parse(file);
                CanonicalModel replaced = next.put(parsed.modelId(), parsed);
                if (replaced == null) {
                    log.info("Loaded canonical model '{}' version {} from {}",
                            parsed.modelId(), parsed.version(), file);
                } else if (replaced.version() != parsed.version()) {
                    log.info("Reloaded canonical model '{}': version {} -> {}",
                            parsed.modelId(), replaced.version(), parsed.version());
                }
            } catch (Exception e) {
                log.error("Failed to (re)load canonical model config {} -- keeping previous version, if any", file, e);
            }
        }
        return Map.copyOf(next);
    }

    /** {@code models} is the just-computed, about-to-be-current model
      * map -- see {@link #reload}'s own comment on why. A client whose
      * feed references a model id absent from it is treated exactly
      * like any other parse failure: logged, that one file's previous
      * good config (if any) stays in place, every other client config
      * still reloads normally. */
    private Map<String, ClientConfig> reloadClients(Map<String, ClientConfig> previous, Map<String, CanonicalModel> models) {
        Map<String, ClientConfig> next = new LinkedHashMap<>(previous);
        for (Path file : listYamlFiles(clientConfigsDir)) {
            try {
                ClientConfig parsed = clientParser.parse(file);
                for (FeedRoute route : parsed.feeds().values()) {
                    if (!models.containsKey(route.modelId())) {
                        throw new CanonicalConfigException(
                                "client '" + parsed.clientId() + "' feed '" + route.feedType()
                                        + "' references unknown canonical model '" + route.modelId() + "': " + file);
                    }
                }
                next.put(parsed.clientId(), parsed);
            } catch (Exception e) {
                log.error("Failed to (re)load client config {} -- keeping previous version, if any", file, e);
            }
        }
        return Map.copyOf(next);
    }

    private Map<FeedRouteKey, FeedRoute> buildRouteIndex(Map<String, ClientConfig> clients) {
        Map<FeedRouteKey, FeedRoute> routes = new LinkedHashMap<>();
        for (ClientConfig client : clients.values()) {
            for (FeedRoute route : client.feeds().values()) {
                routes.put(new FeedRouteKey(client.clientId(), route.feedType()), route);
            }
        }
        return Map.copyOf(routes);
    }

    private List<Path> listYamlFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("Config directory does not exist (yet?): {}", dir);
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Unable to list config directory {}", dir, e);
            return List.of();
        }
    }

    public CanonicalModel get(String modelId) {
        CanonicalModel m = snapshot.models().get(modelId);
        if (m == null) {
            throw new NoSuchElementException("No such canonical model: " + modelId);
        }
        return m;
    }

    public Collection<CanonicalModel> allModels() {
        return snapshot.models().values();
    }

    public ClientConfig getClient(String clientId) {
        ClientConfig c = snapshot.clients().get(clientId);
        if (c == null) {
            throw new NoSuchElementException("No such client config: " + clientId);
        }
        return c;
    }

    public Collection<ClientConfig> allClients() {
        return snapshot.clients().values();
    }

    /** Step 9: resolves a parsed filename's (clientId, feedType) into
      * the route the inbox scanner needs (modelId, expected worksheet
      * names). */
    public FeedRoute resolveRoute(String clientId, String feedType) {
        FeedRoute route = snapshot.routes().get(new FeedRouteKey(clientId, feedType));
        if (route == null) {
            throw new NoSuchElementException(
                    "No feed route for client '" + clientId + "', feed type '" + feedType + "'");
        }
        return route;
    }
}
