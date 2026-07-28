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
 * these files -- every consumer (the future mapping agent's prompt
 * builder, the structured-output schema, the deterministic validator,
 * the dispatcher) reads {@link #get(String)} / {@link #getClient(String)}.
 *
 * This exists specifically to avoid a real failure mode from a prior
 * system: configuration that was "not constant at all," ending up
 * treated as a raw string and re-parsed ad hoc in multiple places. See
 * {@code mapping-notes.md} and {@code canonical-models/SCHEMA.md}'s
 * "Loading &amp; reload" section.
 *
 * Reload is per-file, not all-or-nothing across every config: a file that
 * fails to parse or validate leaves its own previous good model in place
 * (or absent, if it never loaded successfully) and does not affect any
 * other file. A mistake in one team's config can't corrupt another
 * team's pipeline.
 */
@Component
public class CanonicalModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(CanonicalModelRegistry.class);

    private final Path canonicalModelsDir;
    private final Path clientConfigsDir;
    private final CanonicalModelParser modelParser;
    private final ClientConfigParser clientParser;

    private volatile Map<String, CanonicalModel> models = Map.of();
    private volatile Map<String, ClientConfig> clients = Map.of();

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
        reloadModels();
        reloadClients();
    }

    private void reloadModels() {
        Map<String, CanonicalModel> next = new LinkedHashMap<>(this.models);
        for (Path file : listYamlFiles(canonicalModelsDir)) {
            try {
                CanonicalModel parsed = modelParser.parse(file);
                CanonicalModel previous = next.put(parsed.modelId(), parsed);
                if (previous == null) {
                    log.info("Loaded canonical model '{}' version {} from {}",
                            parsed.modelId(), parsed.version(), file);
                } else if (previous.version() != parsed.version()) {
                    log.info("Reloaded canonical model '{}': version {} -> {}",
                            parsed.modelId(), previous.version(), parsed.version());
                }
            } catch (Exception e) {
                log.error("Failed to (re)load canonical model config {} -- keeping previous version, if any", file, e);
            }
        }
        this.models = Map.copyOf(next);
    }

    private void reloadClients() {
        Map<String, ClientConfig> next = new LinkedHashMap<>(this.clients);
        for (Path file : listYamlFiles(clientConfigsDir)) {
            try {
                ClientConfig parsed = clientParser.parse(file);
                next.put(parsed.clientId(), parsed);
            } catch (Exception e) {
                log.error("Failed to (re)load client config {} -- keeping previous version, if any", file, e);
            }
        }
        this.clients = Map.copyOf(next);
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
        CanonicalModel m = models.get(modelId);
        if (m == null) {
            throw new NoSuchElementException("No such canonical model: " + modelId);
        }
        return m;
    }

    public Collection<CanonicalModel> allModels() {
        return models.values();
    }

    public ClientConfig getClient(String clientId) {
        ClientConfig c = clients.get(clientId);
        if (c == null) {
            throw new NoSuchElementException("No such client config: " + clientId);
        }
        return c;
    }

    public Collection<ClientConfig> allClients() {
        return clients.values();
    }
}
