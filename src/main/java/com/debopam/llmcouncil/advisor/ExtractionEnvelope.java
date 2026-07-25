package com.debopam.llmcouncil.advisor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The only shape a language model may reply in.
 *
 * <p>Two properties make this the structural half of "the model produces intent,
 * Java produces configuration".
 *
 * <p><b>There is nowhere to put an id.</b> No component here can hold a model id,
 * a provider name, or a stage type, so a model attempting to name {@code gpt-4o}
 * or {@code DEBATE} has no legal place for it. That is a property of the type
 * rather than of the prompt, which means it survives a model that ignores the
 * prompt entirely.
 *
 * <p><b>An unknown field is an error, not a silent drop.</b> Binding is strict.
 * A model that invents {@code memberModelIds} has its whole reply rejected and
 * retried, rather than having the extra field quietly discarded — because a
 * discarded field is indistinguishable from one that was never sent, and the
 * reply that contained it was answering a different question. {@link #rationale}
 * is the deliberate escape hatch: a chatty model has one legal place to put
 * prose, and what it puts there is shown to the user rather than acted on.
 *
 * <p>Every choice is typed as {@link String} rather than as its enum. Jackson
 * would throw on {@code "privacy": "maybe"} and cost a retry; instead the value
 * is resolved in {@link RequirementExtractor}, which falls back per field and
 * records a note the user sees next to that control. A bad <em>value</em> is a
 * mapping the user can correct in one click; a bad <em>field name</em> means the
 * model was not answering the question.
 *
 * @param privacy             where models may run
 * @param latency             how long the user will wait
 * @param cost                what they will spend
 * @param rigor               how carefully to deliberate
 * @param councilSize         how many members to seat
 * @param domains             what the council is for
 * @param adversarialEmphasis whether to weight the council towards critics
 * @param rationale           the model's own one-line explanation, shown to the
 *                            user and never passed to the synthesizer
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ExtractionEnvelope(
        String privacy,
        String latency,
        String cost,
        String rigor,
        Integer councilSize,
        List<String> domains,
        Boolean adversarialEmphasis,
        String rationale
) {
}
