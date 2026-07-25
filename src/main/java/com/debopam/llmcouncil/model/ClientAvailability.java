package com.debopam.llmcouncil.model;

/**
 * The coarse answer to "can this model actually be called?".
 *
 * <p>Derived from <em>which client was built</em> at startup rather than from
 * inspecting credentials. That is the whole point: a caller asking about
 * availability receives a three-valued enum and never a key, so the read path is
 * structurally incapable of leaking one rather than relying on somebody
 * remembering not to print it.
 *
 * <p>This lives here, next to the clients it classifies, because two callers now
 * need the same answer — the catalog projection and the requirement advisor —
 * and two {@code instanceof} ladders would drift the moment a client type is
 * added.
 */
public enum ClientAvailability {

    /** A real provider client was built; calls go to the provider. */
    LIVE,

    /** No usable credential or provider bean, so calls fail with an explanation. */
    UNAVAILABLE,

    /** A mock client. Always callable, and its output is fabricated. */
    MOCK;

    /**
     * Classify a client by the type that was built for it.
     *
     * @param client the client backing a model, may be null
     * @return {@link #UNAVAILABLE} for a null or unavailable client,
     *         {@link #MOCK} for a mock one, {@link #LIVE} otherwise
     */
    public static ClientAvailability of(ModelClient client) {
        if (client instanceof MockModelClient) {
            return MOCK;
        }
        if (client == null || client instanceof UnavailableModelClient) {
            return UNAVAILABLE;
        }
        return LIVE;
    }

    /** @return {@code true} when this model can be called and its output is real */
    public boolean callable() {
        return this == LIVE;
    }
}
