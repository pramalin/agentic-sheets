// ILLUSTRATIVE SKETCH -- not implementation. Shows the shape the config
// loader would parse every team's YAML into: one generic representation
// that can express ANY team's ADT, not a hand-written class per model.
//
// Java 17+ sealed interfaces and records give real product/sum types on
// the JVM now; Java 21's pattern-matching switch makes handling them
// exhaustive. This is why the backend stays plain Java rather than
// introducing Scala here -- Scala already earns its place on the MCP
// client/test-harness side of this project, but mixing two JVM languages
// inside one Spring app is a real build-complexity cost, and Java's own
// ADT support is good enough for what this needs.

package com.alai.agenticsheets.canonical;

import java.util.List;
import java.util.Map;

// --- The type-level representation: what a config file parses into ---

public sealed interface CanonicalType
        permits PrimitiveType, RecordType, SumType, OptionType {}

public record PrimitiveType(Kind kind, String format) implements CanonicalType {
    public enum Kind { STRING, NUMBER, DATE, BOOLEAN }
    // format is nullable -- absent means "use the kind's default"
    // (yyyy-MM-dd for DATE; no formatting concept for the others yet).
}

public record RecordType(Map<String, CanonicalType> fields) implements CanonicalType {}

public record SumType(Map<String, RecordType> variants) implements CanonicalType {}

public record OptionType(CanonicalType inner) implements CanonicalType {}


// --- The value-level representation: what a mapped spreadsheet row becomes ---

public sealed interface CanonicalValue
        permits StringValue, NumberValue, DateValue, BooleanValue,
                RecordValue, VariantValue, AbsentValue {}

public record StringValue(String value) implements CanonicalValue {}
public record NumberValue(double value) implements CanonicalValue {}
public record DateValue(java.time.LocalDate value) implements CanonicalValue {}
public record BooleanValue(boolean value) implements CanonicalValue {}
public record AbsentValue() implements CanonicalValue {} // the Option "None" case

public record RecordValue(Map<String, CanonicalValue> fields) implements CanonicalValue {}

// Exactly one variant selected, carrying exactly that variant's payload --
// there's no way to construct a VariantValue with two cases filled in, or
// with a case name the SumType it's checked against doesn't declare.
public record VariantValue(String caseName, RecordValue payload) implements CanonicalValue {}


// --- What one team's parsed config becomes, and who owns parsing it ---

public record TargetConfig(
        String service,
        String transport,       // "rest" | "mcp"
        String endpoint,
        String tool,             // only meaningful when transport is "mcp"
        String authType,
        String secretRef) {}

public record CanonicalModel(
        String modelId,
        int version,
        TargetConfig target,
        CanonicalType root) {}

// Sketch only. The real contract: parse+validate happens entirely inside
// reload(); get() always returns a fully-formed CanonicalModel or throws
// (never a half-parsed one), and a failed reload leaves the previously
// held model in place rather than clearing it.
public interface CanonicalModelRegistry {
    CanonicalModel get(String modelId);
    List<CanonicalModel> all();
    void reload(); // parse+validate every configured file; atomic swap on success
}


// --- Where the exhaustiveness actually pays off ---

public final class Validator {
    // Sketch only -- real signature would thread through field paths for
    // error messages. The point is the switch: add a fifth CanonicalType
    // case later (e.g. a ListType) and every validate/render/write call
    // site using this pattern fails to compile until it's handled --
    // not a silent gap discovered in production.
    public boolean validate(CanonicalType type, CanonicalValue value) {
        return switch (type) {
            case PrimitiveType p -> switch (value) {
                case StringValue s -> p.kind() == PrimitiveType.Kind.STRING;
                case NumberValue n -> p.kind() == PrimitiveType.Kind.NUMBER;
                case DateValue d -> p.kind() == PrimitiveType.Kind.DATE;
                case BooleanValue b -> p.kind() == PrimitiveType.Kind.BOOLEAN;
                default -> false;
            };
            case OptionType o -> value instanceof AbsentValue
                    || validate(o.inner(), value);
            case RecordType r -> value instanceof RecordValue rv
                    && r.fields().keySet().equals(rv.fields().keySet())
                    && r.fields().entrySet().stream()
                        .allMatch(e -> validate(e.getValue(), rv.fields().get(e.getKey())));
            case SumType s -> value instanceof VariantValue vv
                    && s.variants().containsKey(vv.caseName())
                    && validate(s.variants().get(vv.caseName()), vv.payload());
        };
    }
}
