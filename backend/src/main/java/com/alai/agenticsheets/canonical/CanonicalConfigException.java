package com.alai.agenticsheets.canonical;

/** Thrown by {@link CanonicalModelParser} and {@link ClientConfigParser}
  * on any parsing or validation failure -- always carries enough context
  * (the offending file, the specific field or type name) to fix the
  * config without needing to read the parser's source. */
public class CanonicalConfigException extends RuntimeException {

    public CanonicalConfigException(String message) {
        super(message);
    }

    public CanonicalConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
