package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.CouncilRunResponse;

import java.util.Optional;

/**
 * Retains the completed result of a council run for later reads.
 *
 * <p>The synchronous run endpoint builds a {@link CouncilRunResponse} from the
 * terminal {@code CouncilContext} and returns it immediately. The chat path
 * cannot do that — it returns as soon as the run is submitted, so by the time a
 * caller asks how much to trust the answer, the context that carried the
 * sycophancy warnings, excluded models, scores and validation verdict has gone
 * out of scope. Without this store those six signals would have to be
 * reassembled client-side from events, the catalog and the artifact files.
 *
 * <p>Implementations must tolerate concurrent writes: results are stored from
 * the virtual thread that ran the council, not from the request thread.
 */
public interface RunResultStore {

    /**
     * Store the result of a finished run, replacing any earlier result.
     *
     * @param sessionId the council session the result belongs to
     * @param result    the completed run result
     */
    void save(String sessionId, CouncilRunResponse result);

    /**
     * Look up the result of a finished run.
     *
     * @param sessionId the council session to read
     * @return the result, or empty when the run has not finished or is unknown
     */
    Optional<CouncilRunResponse> findById(String sessionId);
}
