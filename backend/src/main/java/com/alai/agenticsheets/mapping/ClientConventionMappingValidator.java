package com.alai.agenticsheets.mapping;

import com.alai.agenticsheets.canonical.ClientConfig;
import com.alai.agenticsheets.canonical.ClientModelConventions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The authoritative half of {@code notProvidedFields} enforcement --
 * Local LLM phase, post-benchmark hardening (see
 * {@code docs/local-llm-enhancements.md}'s "twelfth real run" section).
 * {@link CanonicalModelPromptRenderer} omits a client's declared
 * not-provided fields from what the model is shown, which is a real,
 * structural fix for the specific failure it was built from -- but an
 * omission from one prompt is a hint an unrelated confusion could still
 * defeat, not a guarantee: nothing stops a model from producing a path
 * it never saw in this particular prompt from its own general training,
 * and nothing stops a human amending a proposal by hand from typing one
 * in either. This class is the deterministic backstop that makes the
 * convention actually authoritative: a proposal -- model-produced or
 * human-amended, checked the same way either path reaches
 * {@link AgentMappingProposalService} -- that maps a declared
 * not-provided field is rejected outright, regardless of how it got
 * there.
 *
 * <p>Deliberately a separate, small class rather than folded into
 * {@link MappingProposalStructuralValidator} -- an external review's own
 * suggestion. That validator checks a proposal against the canonical
 * ADT itself (is this a real path, a real variant, a real source
 * column); this checks a proposal against one specific client's own
 * configured knowledge about what their feed provides, a different kind
 * of question with a different kind of authority (a per-client
 * convention, not a fact about the schema), worth keeping visibly
 * separate rather than growing one validator to answer two unrelated
 * questions.
 */
@Component
public class ClientConventionMappingValidator {

    /** @return problems found, or an empty list if none -- same
      * contract as {@link MappingProposalStructuralValidator}'s own
      * methods, so a caller aggregates all three the same way before
      * deciding whether to reject. A client with no configured
      * {@code notProvidedFields} for this model (the common case --
      * most clients configure none) short-circuits to an empty list
      * without walking the proposal at all. */
    public List<String> validate(MappingProposal proposal, ClientConfig client, String modelId) {
        ClientModelConventions conventions = client.conventions().get(modelId);
        if (conventions == null || conventions.notProvidedFields().isEmpty()) {
            return List.of();
        }
        Set<String> notProvided = Set.copyOf(conventions.notProvidedFields());

        List<String> problems = new ArrayList<>();
        for (MappingProposal.FieldMapping fm : proposal.fieldMappings()) {
            if (fm != null && notProvided.contains(fm.canonicalFieldPath())) {
                problems.add("'" + fm.canonicalFieldPath() + "' is mapped, but client '" + client.clientId()
                        + "' is configured (notProvidedFields) as never providing this field -- remove this "
                        + "mapping, or update the client config if that's no longer true");
            }
        }
        return problems;
    }
}
