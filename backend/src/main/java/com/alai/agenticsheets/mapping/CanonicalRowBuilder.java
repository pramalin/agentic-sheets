package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.AbsentValue;
import com.alai.agenticsheets.canonical.BooleanValue;
import com.alai.agenticsheets.canonical.CanonicalType;
import com.alai.agenticsheets.canonical.CanonicalValue;
import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.DateValue;
import com.alai.agenticsheets.canonical.NumberValue;
import com.alai.agenticsheets.canonical.OptionType;
import com.alai.agenticsheets.canonical.PrimitiveType;
import com.alai.agenticsheets.canonical.RecordType;
import com.alai.agenticsheets.canonical.RecordValue;
import com.alai.agenticsheets.canonical.StringValue;
import com.alai.agenticsheets.canonical.SumType;
import com.alai.agenticsheets.canonical.VariantValue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Constructs one canonical row's value tree from a source row plus an
 * <em>approved</em> {@link MappingProposal}, validating against the ADT
 * as it goes -- this is Step 7's real enforcement point, the one Step
 * 6's {@code MappingProposalStructuralValidator} explicitly deferred
 * (that check only looked at the proposal's shape, never at what
 * applying it to actual data would produce).
 *
 * Deterministic, no LLM involved. A field construction failure is
 * recorded as an error string and the field becomes {@link AbsentValue}
 * in the tree rather than throwing -- so one bad field doesn't prevent
 * seeing every other problem in the same row in one pass.
 *
 * Date parsing tries ISO (<code>yyyy-MM-dd</code>) first, then the
 * client's configured {@code dateFormat}. This matters because
 * {@code sheets-reader-mcp} returns native Excel date cells already
 * formatted as ISO text regardless of client, while a client's own text
 * cells (not real date cells) come back as whatever raw text was
 * actually typed, which is what {@code client-configs/*.yaml}'s
 * {@code dateFormat} exists to parse.
 */
@Component
public class CanonicalRowBuilder {

    public record Result(CanonicalValue value, List<String> errors) {
        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    public Result build(CanonicalType rootType, Map<String, MappingProposal.FieldMapping> mappingsByPath,
            ClientConfig client, Map<String, String> row) {
        List<String> errors = new ArrayList<>();
        CanonicalValue value = buildValue("", rootType, mappingsByPath, client, row, errors, false);
        return new Result(value, errors);
    }

    private CanonicalValue buildValue(String path, CanonicalType type,
            Map<String, MappingProposal.FieldMapping> mappingsByPath, ClientConfig client,
            Map<String, String> row, List<String> errors, boolean optional) {
        return switch (type) {
            case OptionType o -> buildValue(path, o.inner(), mappingsByPath, client, row, errors, true);
            case PrimitiveType p -> buildPrimitive(path, p, mappingsByPath, client, row, errors, optional);
            case SumType s -> buildSum(path, s, mappingsByPath, client, row, errors, optional);
            case RecordType r -> buildRecordFields(path, r, mappingsByPath, client, row, errors);
        };
    }

    private RecordValue buildRecordFields(String basePath, RecordType r,
            Map<String, MappingProposal.FieldMapping> mappingsByPath, ClientConfig client,
            Map<String, String> row, List<String> errors) {
        Map<String, CanonicalValue> fields = new LinkedHashMap<>();
        for (Map.Entry<String, CanonicalType> entry : r.fields().entrySet()) {
            String fieldPath = basePath.isEmpty() ? entry.getKey() : basePath + "." + entry.getKey();
            fields.put(entry.getKey(), buildValue(fieldPath, entry.getValue(), mappingsByPath, client, row, errors, false));
        }
        return new RecordValue(fields);
    }

    private CanonicalValue buildPrimitive(String path, PrimitiveType p,
            Map<String, MappingProposal.FieldMapping> mappingsByPath, ClientConfig client,
            Map<String, String> row, List<String> errors, boolean optional) {
        String raw = resolveRaw(path, mappingsByPath, row);
        if (raw == null || raw.isBlank()) {
            if (!optional) {
                errors.add("required field '" + path + "' has no mapping or no resolvable source value");
            }
            return new AbsentValue();
        }
        MappingProposal.FieldMapping fm = mappingsByPath.get(path);
        if (fm != null && fm.transformations() != null && !fm.transformations().isEmpty() && p.kind() != PrimitiveType.Kind.NUMBER) {
            errors.add("'" + path + "' proposes a transformation, but only NUMBER fields support one (this "
                    + "field is " + p.kind() + ")");
            return new AbsentValue();
        }
        return switch (p.kind()) {
            case STRING -> new StringValue(raw);
            case BOOLEAN -> {
                if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                    yield new BooleanValue(Boolean.parseBoolean(raw));
                }
                errors.add("'" + path + "' value '" + raw + "' is not a valid boolean");
                yield new AbsentValue();
            }
            case NUMBER -> {
                BigDecimal value;
                try {
                    value = new BigDecimal(raw.trim());
                } catch (NumberFormatException e) {
                    errors.add("'" + path + "' value '" + raw + "' is not a valid number");
                    yield new AbsentValue();
                }
                BigDecimal transformed = applyTransformations(path, value, mappingsByPath.get(path), errors);
                yield transformed == null ? new AbsentValue() : new NumberValue(transformed);
            }
            case DATE -> {
                LocalDate parsed = tryParseDate(raw.trim(), client);
                if (parsed == null) {
                    errors.add("'" + path + "' value '" + raw + "' could not be parsed as a date "
                            + "(tried ISO yyyy-MM-dd and client '" + client.clientId() + "'s format '"
                            + client.dateFormat() + "')");
                    yield new AbsentValue();
                }
                yield new DateValue(parsed);
            }
        };
    }

    /**
     * Applies whitelisted, deterministically-interpreted transformations
     * to an already-parsed number -- see {@link MappingProposal.TransformationStep}'s
     * javadoc for why this exists (a real silent-corruption risk an
     * external review correctly caught: {@code conversionNotes} is free
     * text nothing ever executes). Only {@code "scale"} is recognized;
     * anything else, or a transformation proposed on a field it wasn't
     * checked here, is a validation error, never silently ignored.
     */
    private BigDecimal applyTransformations(String path, BigDecimal value, MappingProposal.FieldMapping fm,
            List<String> errors) {
        if (fm == null || fm.transformations() == null || fm.transformations().isEmpty()) {
            return value;
        }
        BigDecimal result = value;
        for (MappingProposal.TransformationStep step : fm.transformations()) {
            if (!"scale".equals(step.type())) {
                errors.add("'" + path + "' proposes an unrecognized transformation type '" + step.type()
                        + "' -- only 'scale' is currently implemented");
                return null;
            }
            try {
                result = result.multiply(new BigDecimal(step.multiplier().trim()));
            } catch (NumberFormatException | NullPointerException e) {
                errors.add("'" + path + "' has a 'scale' transformation with an unparseable multiplier '"
                        + step.multiplier() + "'");
                return null;
            }
        }
        return result;
    }

    private LocalDate tryParseDate(String raw, ClientConfig client) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException isoFailed) {
            try {
                return LocalDate.parse(raw, DateTimeFormatter.ofPattern(client.dateFormat()));
            } catch (Exception clientFormatFailed) {
                return null;
            }
        }
    }

    private CanonicalValue buildSum(String path, SumType s,
            Map<String, MappingProposal.FieldMapping> mappingsByPath, ClientConfig client,
            Map<String, String> row, List<String> errors, boolean optional) {
        MappingProposal.FieldMapping fm = mappingsByPath.get(path);
        if (fm == null) {
            if (!optional) {
                errors.add("required sum type field '" + path + "' has no mapping");
            }
            return new AbsentValue();
        }

        String variantName = resolveVariant(path, fm, row, errors);
        if (variantName == null) {
            return new AbsentValue(); // the specific problem was already recorded by resolveVariant
        }

        RecordType variantType = s.variants().get(variantName);
        if (variantType == null) {
            errors.add("'" + path + "' resolved to variant '" + variantName + "', which isn't one of "
                    + s.variants().keySet());
            return new AbsentValue();
        }

        RecordValue payload = buildRecordFields(path + "." + variantName, variantType, mappingsByPath, client, row, errors);
        return new VariantValue(variantName, payload);
    }

    private String resolveVariant(String path, MappingProposal.FieldMapping fm, Map<String, String> row,
            List<String> errors) {
        boolean hasSelected = isSet(fm.selectedVariant());
        boolean hasMap = fm.variantValueMap() != null && !fm.variantValueMap().isEmpty();

        if (hasSelected) {
            return fm.selectedVariant();
        }
        if (hasMap) {
            if (!isSet(fm.sourceColumn())) {
                errors.add("'" + path + "' has a variantValueMap but no sourceColumn to read the row's value from");
                return null;
            }
            String raw = row.get(fm.sourceColumn());
            String variant = fm.variantValueMap().get(raw);
            if (variant == null) {
                errors.add("'" + path + "' row value '" + raw + "' is not one of the mapped values "
                        + fm.variantValueMap().keySet());
                return null;
            }
            return variant;
        }
        errors.add("'" + path + "' is a sum type field with neither selectedVariant nor variantValueMap set");
        return null;
    }

    private String resolveRaw(String path, Map<String, MappingProposal.FieldMapping> mappingsByPath,
            Map<String, String> row) {
        MappingProposal.FieldMapping fm = mappingsByPath.get(path);
        if (fm == null) {
            return null;
        }
        if (isSet(fm.sourceColumn())) {
            return row.get(fm.sourceColumn());
        }
        if (isSet(fm.sourceConstant())) {
            return fm.sourceConstant();
        }
        return null;
    }

    private boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
