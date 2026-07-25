package com.debopam.llmcouncil.advisor;

/**
 * A request the advisor refuses to act on.
 *
 * <p>Thrown from {@link AdvisorService} rather than from a controller, because
 * these are rules about the advisor and not about HTTP. A command-line caller
 * has to be held to the same ones — most of all the acknowledgement before a
 * description is sent to a third party, which is not a gate if it lives in a
 * web page.
 *
 * @param remediation what the caller should do instead; may be null
 */
public class AdvisorRequestException extends RuntimeException {

    private final String remediation;

    /**
     * @param message     what is wrong, phrased for the person who asked
     * @param remediation what to do about it, or null when the message says it
     */
    public AdvisorRequestException(String message, String remediation) {
        super(message);
        this.remediation = remediation;
    }

    /** @return the suggested next step, or null when there is none */
    public String remediation() {
        return remediation;
    }
}
