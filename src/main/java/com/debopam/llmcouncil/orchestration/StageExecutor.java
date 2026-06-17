package com.debopam.llmcouncil.orchestration;

/**
 * Strategy interface for one protocol stage.
 *
 * <p>Implementations must be:
 * <ul>
 *   <li><b>Stateless</b> — all state lives in {@link CouncilContext}.</li>
 *   <li><b>Spring components</b> — annotated with {@code @Component} so
 *       {@link StageExecutorRegistry} can discover them automatically.</li>
 * </ul>
 */
public interface StageExecutor {

    /**
     * @return The {@link StageType} this executor handles.
     */
    StageType stage();

    /**
     * Execute this stage, mutating {@code context} with produced artifacts.
     *
     * @param context The shared council context.
     * @param options Stage-specific options from the protocol definition.
     * @return The (potentially mutated) context; callers must use the returned reference.
     * @throws Exception Any unrecoverable error; the orchestrator will emit
     *                   a {@code STAGE_FAILED} event and mark the context terminal.
     */
    CouncilContext execute(CouncilContext context, ProtocolStageOptions options) throws Exception;
}
