package com.alai.agenticsheets.canonical;

import java.util.Map;

public record RecordValue(Map<String, CanonicalValue> fields) implements CanonicalValue {
}
