package com.alai.agenticsheets.mapping;

import java.util.List;

/** Thrown by {@link MappingProposalService} when
  * {@link MappingProposalStructuralValidator} finds the agent's output
  * doesn't actually conform to the ADT it was shown -- caught by
  * {@link MappingController}, which returns 422 with the problem list
  * rather than persisting a proposal nothing downstream could trust. */
public class MappingProposalValidationException extends RuntimeException {

    private final List<String> problems;

    public MappingProposalValidationException(List<String> problems) {
        super("Mapping proposal failed structural validation: " + String.join("; ", problems));
        this.problems = problems;
    }

    public List<String> problems() {
        return problems;
    }
}
