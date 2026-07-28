package com.alai.agenticsheets.canonical;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

/** Read-only visibility into what {@link CanonicalModelRegistry} has
  * currently loaded -- not part of the mapping pipeline itself, just a
  * way to confirm configuration actually parsed the way you expect
  * without reading application logs. */
@RestController
@RequestMapping("/internal/canonical")
public class CanonicalModelController {

    private final CanonicalModelRegistry registry;

    public CanonicalModelController(CanonicalModelRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/models")
    public List<ModelSummary> models() {
        return registry.allModels().stream()
                .map(m -> new ModelSummary(m.modelId(), m.version(),
                        m.target().service(), m.target().transport(), m.sourceFile().toString()))
                .toList();
    }

    @GetMapping("/models/{modelId}")
    public CanonicalModel model(@PathVariable String modelId) {
        // Full object graph, including the nested CanonicalType tree --
        // deliberately verbose. This is a debug endpoint, not a public API.
        return registry.get(modelId);
    }

    @GetMapping("/clients")
    public Collection<ClientConfig> clients() {
        return registry.allClients();
    }

    public record ModelSummary(String modelId, int version, String targetService, String transport, String sourceFile) {
    }
}
