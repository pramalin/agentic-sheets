package com.alai.agenticsheets.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * FOR LOCAL TESTING ONLY. Simulates a team's receiving service so
 * {@link Dispatcher} has something real to call without needing external
 * network access or a second running service -- {@code holdings.yaml}'s
 * {@code target.endpoint} points here for exactly that reason. A real
 * team's target lives entirely outside this project, on infrastructure
 * this system has no knowledge of (see {@code SCHEMA.md}'s "Target
 * service" section) -- this endpoint exists purely so Step 7's dispatch
 * path is actually exercisable, not as a model for how a real receiver
 * should be built.
 */
@RestController
@RequestMapping("/internal/fake-target")
public class FakeTargetController {

    private static final Logger log = LoggerFactory.getLogger(FakeTargetController.class);

    @PostMapping("/{service}")
    public Map<String, Object> receive(
            @PathVariable String service,
            @RequestBody String body,
            @RequestHeader Map<String, String> headers) {
        log.info("fake-target[{}] received {} bytes, X-Import-Batch-Id={}",
                service, body.length(), headers.get("x-import-batch-id"));
        return Map.of("received", true, "service", service, "bytes", body.length());
    }
}
