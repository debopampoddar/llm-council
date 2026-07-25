package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.model.ModelProfile;

import java.util.List;
import java.util.Locale;

/**
 * Guesses a model family from a provider model id.
 *
 * <p>Needed only for a model this configuration is about to <em>define</em> — an
 * installed Ollama tag that nothing binds yet. A defined model with no
 * {@code modelFamily} would trip the validator's diversity warning and, worse,
 * would make {@code ValidationIndependence} unable to say whether a validator
 * can catch the chair's mistakes.
 *
 * <p><b>A guessed family is a guessed trust signal.</b> The dangerous error is
 * not mislabelling one model, it is two models of the same family reading as two
 * families, which makes a council look more able to disagree with itself than it
 * is. Every inference is therefore reported as a warning against the model that
 * carries it, rather than being quietly correct most of the time.
 */
final class ModelFamilyHeuristic {

    /**
     * Known family prefixes, longest first so {@code deepseek-coder} is not read
     * as {@code deepseek} when both are listed.
     */
    private static final List<String> KNOWN_FAMILIES = List.of(
            "deepseek", "codellama", "llama", "mistral", "mixtral", "qwen", "gemma",
            "phi", "granite", "command-r", "nemotron", "falcon", "yi", "vicuna",
            "orca", "solar", "starcoder", "wizardlm", "openchat", "tinyllama",
            "smollm", "olmo", "exaone", "glm", "internlm", "minicpm");

    private ModelFamilyHeuristic() {
    }

    /**
     * Infer a family tag from a provider model id.
     *
     * @param providerModelId the tag as the provider names it, for example
     *                        {@code qwen2.5:14b}
     * @return a normalised family tag; never blank for a non-blank input
     */
    static String infer(String providerModelId) {
        if (providerModelId == null || providerModelId.isBlank()) {
            return null;
        }
        // The size suffix and any registry path are noise: library/qwen2.5:14b
        // and qwen2.5:14b are the same family.
        String name = providerModelId.trim().toLowerCase(Locale.ROOT);
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(0, colon);
        }

        for (String family : KNOWN_FAMILIES) {
            if (name.startsWith(family)) {
                return family;
            }
        }

        // Unknown: fall back to the leading run of letters, which is what a
        // version-suffixed name reduces to. "hermes3" becomes "hermes".
        StringBuilder leading = new StringBuilder();
        for (char character : name.toCharArray()) {
            if (!Character.isLetter(character)) {
                break;
            }
            leading.append(character);
        }
        String fallback = leading.isEmpty() ? name : leading.toString();
        return ModelProfile.normaliseFamily(fallback);
    }
}
