package com.alai.agenticsheets.canonical;

/**
 * The {@code target:} block of a canonical model config -- where an
 * approved, validated payload gets sent. The system never persists this
 * data itself; see {@code canonical-models/SCHEMA.md}'s "Target service"
 * section.
 */
public record TargetConfig(
        String service,
        String transport,    // "rest" | "mcp"
        String endpoint,
        String tool,          // only meaningful when transport is "mcp"
        String authType,
        String secretRef,
        DeliveryConfig delivery) {
}
