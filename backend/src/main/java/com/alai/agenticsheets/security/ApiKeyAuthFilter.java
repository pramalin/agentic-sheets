package com.alai.agenticsheets.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A single shared secret, checked as a bearer token, on every
 * {@code /internal/**} request -- deliberately not the multi-tenant,
 * multiple-identity-provider auth {@code ui-notes.md} discusses for the
 * eventual separate embeddable-widget project. Step 8's UI is
 * integrated into this project itself, for a single organization; "no
 * auth at all" stopped being acceptable the moment approving a mapping
 * became something a UI puts a click in front of, but the multi-tenant
 * complexity that requirement never actually applies here.
 *
 * Fails closed if unconfigured: a blank {@code agentic-sheets.api-key}
 * rejects every request rather than silently letting everything
 * through, since a bug that left this blank in a real deployment should
 * be loud, not invisible.
 *
 * {@code /internal/fake-target/**} is deliberately excluded --
 * {@link com.alai.agenticsheets.mapping.Dispatcher} calls it with a
 * *different* secret ({@code HOLDINGS_WRITER_API_KEY}, simulating a
 * team's own auth), and requiring this filter's secret there too would
 * make the backend's own outbound delivery calls fail this filter's
 * check. {@code /actuator/**} is excluded for the same reason health
 * checks generally are -- infrastructure needs to probe it without
 * carrying an application-level credential.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String configuredKey;

    public ApiKeyAuthFilter(@Value("${agentic-sheets.api-key:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/internal/") || path.startsWith("/internal/fake-target/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader("Authorization");
        String expected = "Bearer " + configuredKey;

        boolean valid = !configuredKey.isBlank() && provided != null && constantTimeEquals(provided, expected);
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"problems\":[\"missing or invalid Authorization header\"]}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Constant-time comparison -- a plain {@code String.equals} short-
      * circuits on the first mismatched character, which leaks timing
      * information about how much of a guessed secret was correct.
      * Appropriately modest for a single shared secret; not a claim this
      * is enterprise-grade credential handling. */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
