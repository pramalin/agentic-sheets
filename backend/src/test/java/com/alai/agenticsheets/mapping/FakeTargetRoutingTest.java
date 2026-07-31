package com.alai.agenticsheets.mapping;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real Spring MVC slice test -- actual URL dispatch through a real
 * {@code DispatcherServlet}, not {@link FakeTargetControllerTest}'s
 * direct-object-construction unit tests. Exists specifically because an
 * external review correctly pointed out those unit tests "verify logic
 * but not conditional registration or URL routing" -- and were right to
 * be skeptical: this test locks in a real routing bug that direct
 * object construction could never have caught.
 *
 * No {@code agentic-sheets.fake-target.journal-enabled} property is set
 * here, matching a normal (non-E2E) deployment -- {@link
 * FakeTargetJournal} and {@link FakeTargetJournalController} should
 * both be entirely absent from this context.
 *
 * The bug this locks in: the journal's read/reset endpoints originally
 * lived directly under {@code /internal/fake-target}, meaning {@code
 * POST /internal/fake-target/reset} was only reachable while {@link
 * FakeTargetJournalController}'s bean existed. With the property unset,
 * {@link FakeTargetController}'s {@code @PostMapping("/{service}")} was
 * the *only* handler left registered for that URL shape, and Spring
 * matched it with {@code service = "reset"} instead of returning 404 --
 * a request meant to hit a now-nonexistent endpoint was silently
 * treated as an ordinary fake-target delivery instead. Moving the
 * journal under {@code /_journal} (a namespace {@code /{service}} can
 * never match, being single-segment) fixed it.
 *
 * A first version of this file also asserted that the plain,
 * unnamespaced {@code /internal/fake-target/reset} should itself return
 * 404 -- that assertion was simply wrong, caught by actually running
 * it rather than left in place. Once the journal is namespaced under
 * {@code /_journal}, there is no longer anything at the plain
 * {@code /reset} path for {@code /{service}} to collide with --
 * {@code service = "reset"} is now exactly as valid and unambiguous as
 * any other service name, the same way a real deployment might
 * genuinely have a client or service called "reset". The test below
 * asserts that directly: an ordinary delivery to that URL succeeds like
 * any other, not that it's somehow still special-cased.
 */
@WebMvcTest(controllers = {FakeTargetController.class, FakeTargetJournalController.class})
class FakeTargetRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void journalRequestsUrlDoesNotExistWhenPropertyUnset() throws Exception {
        mockMvc.perform(get("/internal/fake-target/_journal/holdings/requests"))
                .andExpect(status().isNotFound());
    }

    @Test
    void journalResetUrlDoesNotExistWhenPropertyUnset() throws Exception {
        mockMvc.perform(post("/internal/fake-target/_journal/reset"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theOldUnnamespacedResetUrlIsJustAnOrdinaryServiceNameNow() throws Exception {
        // Confirms the namespace change actually resolved the
        // ambiguity, rather than just moving it: "reset" is no longer
        // a special or reserved word at this path at all, it's exactly
        // as valid a service name as "holdings" -- a real client could
        // legitimately be named this.
        mockMvc.perform(post("/internal/fake-target/reset").content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    void theReceiverStillWorksNormallyForRealServiceNames() throws Exception {
        mockMvc.perform(post("/internal/fake-target/holdings").content("[]"))
                .andExpect(status().isOk());
    }
}
