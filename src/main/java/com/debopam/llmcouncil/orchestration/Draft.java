package com.debopam.llmcouncil.orchestration;

/**
 * A draft answer produced by one council member during {@link StageType#GENERATE}
 * or refined during {@link StageType#AGGREGATE}.
 *
 * @param draftId   Unique identifier for this draft (typically modelId or modelId+"_agg").
 * @param modelId   The model that produced this draft.
 * @param text      The raw answer text.
 * @param anonymous Whether this draft has been anonymised (model identity hidden from reviewers).
 */
public record Draft(String draftId, String modelId, String text, boolean anonymous) {

    /** Convenience constructor — draft starts non-anonymous. */
    public Draft(String draftId, String modelId, String text) {
        this(draftId, modelId, text, false);
    }

    /** Returns a copy of this draft with anonymous=true. */
    public Draft anonymised() {
        return new Draft(draftId, modelId, text, true);
    }
}
