package com.alai.agenticsheets.canonical;

import java.util.Map;

/**
 * One complete, internally-consistent configuration generation --
 * models, clients, and the {@code (clientId, feedType) -> FeedRoute}
 * index built from them, published together as a single atomic
 * reference swap (see {@link CanonicalModelRegistry}).
 *
 * Replacing this instead of two independently-updated fields closes a
 * real race an external review of Step 9's design caught: with
 * {@code models} and {@code clients} as two separate {@code volatile}
 * fields, swapped one after the other, a reader between the two swaps
 * could observe a new model map alongside a stale client map (or the
 * reverse) -- harmless while the two were unrelated, but no longer
 * once a client's feed route needs to reference a model from the same
 * reload cycle.
 */
public record RegistrySnapshot(
        Map<String, CanonicalModel> models,
        Map<String, ClientConfig> clients,
        Map<FeedRouteKey, FeedRoute> routes) {

    public static final RegistrySnapshot EMPTY = new RegistrySnapshot(Map.of(), Map.of(), Map.of());
}
