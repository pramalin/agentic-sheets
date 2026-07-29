package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.AbsentValue;
import com.alai.agenticsheets.canonical.BooleanValue;
import com.alai.agenticsheets.canonical.CanonicalValue;
import com.alai.agenticsheets.canonical.DateValue;
import com.alai.agenticsheets.canonical.NumberValue;
import com.alai.agenticsheets.canonical.RecordValue;
import com.alai.agenticsheets.canonical.StringValue;
import com.alai.agenticsheets.canonical.VariantValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a constructed {@link CanonicalValue} tree into a plain
 * Java structure ({@code Map}/{@code List}/primitives) a JSON mapper can
 * serialize directly -- this is the actual wire payload a team's service
 * receives.
 *
 * A sum type value has no native JSON representation, so this picks one:
 * a discriminated object, {@code {"type": "<VariantName>", ...fields}}.
 * That choice is a real part of the wire contract, not an implementation
 * detail -- a receiving service needs to know it, so it's documented in
 * {@code canonical-models/SCHEMA.md}'s "Target service" section too, not
 * just here.
 *
 * Dates serialize as ISO-8601 ({@code LocalDate.toString()}) regardless
 * of a primitive's declared {@code format} -- every current canonical
 * model's Date fields use the default {@code yyyy-MM-dd}, which happens
 * to coincide with ISO, so this hasn't mattered yet. Honoring a
 * non-default declared format on output is a known gap, not something
 * silently assumed to be handled.
 */
public final class CanonicalValueJson {

    private CanonicalValueJson() {
    }

    public static Object toJsonCompatible(CanonicalValue value) {
        return switch (value) {
            case StringValue s -> s.value();
            case NumberValue n -> n.value();
            case DateValue d -> d.value().toString();
            case BooleanValue b -> b.value();
            case AbsentValue a -> null;
            case RecordValue r -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Map.Entry<String, CanonicalValue> e : r.fields().entrySet()) {
                    map.put(e.getKey(), toJsonCompatible(e.getValue()));
                }
                yield map;
            }
            case VariantValue v -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", v.caseName());
                for (Map.Entry<String, CanonicalValue> e : v.payload().fields().entrySet()) {
                    map.put(e.getKey(), toJsonCompatible(e.getValue()));
                }
                yield map;
            }
        };
    }
}
