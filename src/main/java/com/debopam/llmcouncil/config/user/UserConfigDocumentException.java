package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;

import java.util.List;

/**
 * A document that could not be read at all.
 *
 * <p>Distinct from validation failure. A document that parses but breaks a rule
 * produces a report listing every problem, because a user wants to fix them all
 * in one pass. A document that cannot be parsed, or that carries credential
 * material, has no rules to check yet — so it is refused whole.
 *
 * <p>Carries {@link ConfigIssue}s rather than a message so the API answers in the
 * same shape either way, and so nothing has to be re-derived from prose. The
 * issues never contain the offending value.
 */
public class UserConfigDocumentException extends RuntimeException {

    private final transient List<ConfigIssue> issues;

    /**
     * @param issues why the document was refused; must not be empty
     */
    public UserConfigDocumentException(List<ConfigIssue> issues) {
        super(issues.isEmpty() ? "The document was refused." : issues.get(0).message());
        this.issues = List.copyOf(issues);
    }

    /** @return every reason the document was refused */
    public List<ConfigIssue> issues() {
        return issues;
    }
}
