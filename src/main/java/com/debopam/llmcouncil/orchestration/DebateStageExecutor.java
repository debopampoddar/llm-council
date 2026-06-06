/**
 * Auto-generated documentation for DebateStageExecutor.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DebateStageExecutor implements StageExecutor {
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final StructuredReviewParser parser;
    private final DebateTriggerPolicy triggerPolicy;
    private final ArtifactStore artifactStore;
    private final EventPublisher events;

    public DebateStageExecutor(
            ModelRegistry registry,
            StructuredReviewParser parser,
            DebateTriggerPolicy triggerPolicy,
            ArtifactStore artifactStore,
            EventPublisher events
    ) {
        this.registry = registry;
        this.parser = parser;
        this.triggerPolicy = triggerPolicy;
        this.artifactStore = artifactStore;
        this.events = events;
    }

    @Override
    public StageType stage() {
        return StageType.DEBATE;
    }

    @Override
    public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
        DebateTriggerPolicy.DebateDecision decision = triggerPolicy.shouldRun(context, options);
        if (!decision.run()) {
            DebateSummary skipped = DebateSummary.skipped(decision.reason());
            context.setDebateSummary(skipped);
            artifactStore.writeJson(context.session().id(), "normalized/debate-summary.json", skipped);
            events.publish(context.session().id(), stage().name(), "DEBATE_SKIPPED", null, Map.of("reason", decision.reason()));
            return context;
        }

        Set<String> validDraftIds = context.anonymizedDraftSet().drafts().stream()
                .map(AnonymizedDraft::draftId)
                .collect(Collectors.toSet());

        List<DebateRound> rounds = new ArrayList<>();
        DebateSummary runningSummary = DebateSummary.skipped("No previous debate round.");
        int maxRounds = options.maxRoundsOrDefault(2);

        for (int round = 1; round <= maxRounds; round++) {
            List<DebateContribution> contributions = new ArrayList<>();
            for (String participantModelId : context.profile().memberModelIds()) {
                ModelProfile participant = registry.model(participantModelId);
                ModelCallResult result = registry.clientForModel(participant.id()).call(new ModelCallRequest(
                        context.session().id(),
                        stage().name(),
                        participant.id(),
                        participant.providerModelId(),
                        promptBuilder.debateMessages(
                                participant.id(),
                                context.session().question(),
                                context.anonymizedDraftSet(),
                                context.scoreSummary(),
                                runningSummary,
                                round
                        ),
                        participant.defaultOutputTokens(),
                        0.1,
                        true,
                        Duration.ofSeconds(150)
                ));

                artifactStore.writeText(
                        context.session().id(),
                        "raw/debate-round-" + round + "-" + participant.id() + ".json",
                        result.text()
                );

                DebateModelOutput output = parser.parseDebate(participant.id(), result.text(), validDraftIds);
                contributions.add(new DebateContribution(
                        participant.id(),
                        output.position(),
                        nullToEmpty(output.supportedDraftIds()),
                        nullToEmpty(output.challengedDraftIds()),
                        nullToEmpty(output.newEvidence()),
                        nullToEmpty(output.unresolvedRisks()),
                        output.changedPosition(),
                        output.changeReason(),
                        output.confidence()
                ));

                events.publish(context.session().id(), stage().name(), "DEBATE_CONTRIBUTION", participant.id(), Map.of("round", round));
            }
            rounds.add(new DebateRound(round, List.copyOf(contributions)));
            runningSummary = summarize(rounds, false, null);
        }

        DebateSummary finalSummary = summarize(rounds, false, null);
        context.setDebateSummary(finalSummary);
        artifactStore.writeJson(context.session().id(), "normalized/debate-summary.json", finalSummary);
        events.publish(context.session().id(), stage().name(), "DEBATE_COMPLETED", null, Map.of("rounds", rounds.size()));
        return context;
    }

    private DebateSummary summarize(List<DebateRound> rounds, boolean skipped, String skipReason) {
        List<String> consensus = new ArrayList<>();
        List<String> dissent = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> instructions = new ArrayList<>();

        for (DebateRound round : rounds) {
            for (DebateContribution contribution : round.contributions()) {
                if (contribution.newEvidence() != null) {
                    consensus.addAll(contribution.newEvidence());
                }
                if (contribution.challengedDraftIds() != null && !contribution.challengedDraftIds().isEmpty()) {
                    dissent.add(contribution.modelId() + " challenged " + contribution.challengedDraftIds() + ": " + contribution.position());
                }
                if (contribution.unresolvedRisks() != null) {
                    risks.addAll(contribution.unresolvedRisks());
                }
            }
        }

        if (!risks.isEmpty()) {
            instructions.add("The final answer must explicitly address unresolved risks.");
        }
        if (!dissent.isEmpty()) {
            instructions.add("The final answer must preserve material dissent instead of forcing consensus.");
        }

        return new DebateSummary(
                skipped,
                skipReason,
                List.copyOf(rounds),
                distinctLimit(consensus, 12),
                distinctLimit(dissent, 12),
                distinctLimit(risks, 12),
                distinctLimit(instructions, 6)
        );
    }

    private List<String> distinctLimit(List<String> values, int limit) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
