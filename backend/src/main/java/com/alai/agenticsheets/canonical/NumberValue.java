package com.alai.agenticsheets.canonical;

import java.math.BigDecimal;

/** {@code BigDecimal}, not {@code double} -- this represents financial
  * quantities (market values, quantities, rates); floating-point
  * rounding error has no place here. */
public record NumberValue(BigDecimal value) implements CanonicalValue {
}
