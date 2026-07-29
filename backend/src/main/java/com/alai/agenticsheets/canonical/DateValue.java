package com.alai.agenticsheets.canonical;

import java.time.LocalDate;

public record DateValue(LocalDate value) implements CanonicalValue {
}
