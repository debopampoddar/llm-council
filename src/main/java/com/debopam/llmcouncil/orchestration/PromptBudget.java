package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.model.ModelProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fits variable-length prompt sections into a model's context window.
 *
 * <p>Council prompts grow with the size of the council: the chair's synthesis
 * prompt carries every draft, every review, every score line, and every debate
 * turn. Nothing previously bounded that, while local models run with a 4096
 * token window by default. A provider given more than its window does not
 * report an error — Ollama silently discards context — so the failure mode is
 * an answer quietly synthesised from a fragment of the evidence, with no signal
 * anywhere that it happened.
 *
 * <p>This class makes that overflow visible and deterministic instead:
 * instructions and the user's question are never truncated, the remaining space
 * is shared fairly across the variable sections, and anything actually removed
 * is both marked in the text and recorded as a notice for the caller to surface.
 *
 * <p><b>Not thread-safe.</b> Create one per prompt construction; the fan-out
 * stages build one per virtual thread.
 */
public final class PromptBudget {

    /**
     * Characters assumed per token when converting a token budget to a character
     * budget.
     *
     * <p>English averages roughly four characters per token, so assuming 3.5
     * deliberately claims less room than the model really has. Erring this way
     * truncates slightly earlier than necessary; erring the other way overflows
     * the window and loses content silently, which is the failure this class
     * exists to prevent.
     */
    private static final double CHARS_PER_TOKEN = 3.5;

    /** Head-room for chat scaffolding and tokenizer variance, in tokens. */
    private static final int SAFETY_MARGIN_TOKENS = 512;

    /** Smallest useful slice of a section; below this the item is dropped whole. */
    private static final int MINIMUM_ITEM_CHARS = 200;

    private final int totalChars;
    private final boolean unlimited;
    private final String modelId;
    private final List<String> notices = new ArrayList<>();

    private PromptBudget(int totalChars, boolean unlimited, String modelId) {
        this.totalChars = totalChars;
        this.unlimited = unlimited;
        this.modelId = modelId;
    }

    /**
     * Derive a budget from the model that will receive the prompt.
     *
     * <p>The window must also hold the model's own response, so the reserved
     * output tokens and the safety margin are subtracted before conversion.
     *
     * @param model the model the prompt is being built for
     * @return a budget for this model, or an unlimited budget when the model
     *         declares no context window
     */
    public static PromptBudget forModel(ModelProfile model) {
        if (model == null || model.contextWindowTokens() <= 0) {
            return unlimited();
        }
        int usableTokens = model.contextWindowTokens()
                           - model.defaultOutputTokens()
                           - SAFETY_MARGIN_TOKENS;
        if (usableTokens <= 0) {
            // The model cannot even hold its own configured output. Report it
            // rather than producing a nonsensical negative budget.
            PromptBudget budget = new PromptBudget(0, false, model.id());
            budget.notices.add(("Model %s reserves %d output tokens from a %d token context window, "
                                + "leaving no room for a prompt. Lower defaultOutputTokens or raise "
                                + "contextWindowTokens.")
                                       .formatted(model.id(), model.defaultOutputTokens(),
                                                  model.contextWindowTokens()));
            return budget;
        }
        return new PromptBudget((int) (usableTokens * CHARS_PER_TOKEN), false, model.id());
    }

    /**
     * A budget that never truncates.
     *
     * @return an unlimited budget, used when no context window is configured
     */
    public static PromptBudget unlimited() {
        return new PromptBudget(Integer.MAX_VALUE, true, null);
    }

    /** @return {@code true} when this budget never truncates */
    public boolean isUnlimited() {
        return unlimited;
    }

    /** @return the total character budget available for a whole prompt */
    public int totalChars() {
        return totalChars;
    }

    /**
     * Notices describing what this budget removed.
     *
     * <p>Empty when nothing was truncated. Callers surface these on the council
     * context so a degraded prompt is visible in the run result rather than only
     * in logs.
     *
     * @return truncation notices accumulated so far
     */
    public List<String> notices() {
        return List.copyOf(notices);
    }

    /** @return {@code true} if this budget removed any content */
    public boolean truncated() {
        return !notices.isEmpty();
    }

    /**
     * Fit named sections of rendered items into the remaining budget.
     *
     * <p>Space is allocated max-min fair: every section starts with an equal
     * share, sections needing less than their share release the surplus, and the
     * remainder is redistributed to sections that still want more. A short score
     * summary therefore cannot strand a quarter of the window while the drafts
     * are being cut. The same rule then applies to items within a section, so one
     * very long draft cannot starve the others.
     *
     * @param reservedChars characters already committed to instructions, the
     *                      question, and template scaffolding — never truncated
     * @param sections      section name to rendered items, in template order
     * @return the same sections with items truncated as needed, preserving order
     */
    public Map<String, List<String>> fit(int reservedChars, Map<String, List<String>> sections) {
        if (unlimited) {
            return sections;
        }

        int available = totalChars - reservedChars;
        int required = sections.values().stream()
                               .flatMap(List::stream)
                               .mapToInt(String::length)
                               .sum();
        if (required <= available) {
            return sections;
        }
        if (available <= 0) {
            notices.add(("Prompt for model %s has no room for council evidence: instructions and question "
                         + "alone need %d characters of a %d character budget.")
                                .formatted(modelId, reservedChars, totalChars));
            available = 0;
        }

        Map<String, Integer> sectionSizes = new LinkedHashMap<>();
        sections.forEach((name, items) ->
                sectionSizes.put(name, items.stream().mapToInt(String::length).sum()));
        Map<String, Integer> sectionBudgets = fairShare(sectionSizes, available);

        Map<String, List<String>> result = new LinkedHashMap<>();
        sections.forEach((name, items) -> result.put(name, fitSection(name, items, sectionBudgets.get(name))));

        notices.add(("Prompt for model %s exceeded its context budget: %d characters of council evidence "
                     + "were reduced to %d to fit a %d character window. The answer is synthesised from "
                     + "truncated evidence.")
                            .formatted(modelId, required, available, totalChars));
        return result;
    }

    private List<String> fitSection(String sectionName, List<String> items, int sectionBudget) {
        int required = items.stream().mapToInt(String::length).sum();
        if (required <= sectionBudget) {
            return items;
        }

        Map<String, Integer> itemSizes = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            itemSizes.put(String.valueOf(i), items.get(i).length());
        }
        Map<String, Integer> itemBudgets = fairShare(itemSizes, sectionBudget);

        List<String> result = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            result.add(truncate(items.get(i), itemBudgets.get(String.valueOf(i))));
        }
        return result;
    }

    /**
     * Truncate one item, marking what was removed.
     *
     * <p>The marker matters: a downstream model reading a fragment with no
     * indication it is a fragment will reason over it as if complete.
     */
    private String truncate(String text, int budget) {
        if (text.length() <= budget) {
            return text;
        }
        if (budget < MINIMUM_ITEM_CHARS) {
            return "[... omitted: %d characters did not fit the context budget ...]".formatted(text.length());
        }
        String marker = "\n\n[... truncated: %d characters omitted ...]";
        int keep = Math.max(0, budget - marker.length() - 8);
        int boundary = text.lastIndexOf("\n\n", keep);
        if (boundary > keep / 2) {
            keep = boundary;
        }
        return text.substring(0, keep) + marker.formatted(text.length() - keep);
    }

    /**
     * Max-min fair allocation of a budget across claimants.
     *
     * <p>Each claimant is offered an equal share. Anyone wanting less than their
     * share takes only what they need and returns the surplus, which is then
     * redistributed among those still unsatisfied. Repeats until no claimant can
     * be satisfied from its share.
     *
     * @param sizes  claimant name to requested size
     * @param budget total available
     * @return claimant name to granted size
     */
    private static Map<String, Integer> fairShare(Map<String, Integer> sizes, int budget) {
        Map<String, Integer> granted = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(sizes.keySet());
        int remaining = budget;

        boolean progressed = true;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            int share = remaining / pending.size();
            List<String> stillPending = new ArrayList<>();
            for (String name : pending) {
                int wanted = sizes.get(name);
                if (wanted <= share) {
                    granted.put(name, wanted);
                    remaining -= wanted;
                    progressed = true;
                } else {
                    stillPending.add(name);
                }
            }
            pending = stillPending;
        }

        // Whatever is left is split evenly among claimants that wanted more than
        // their share; each will be truncated to that figure.
        if (!pending.isEmpty()) {
            int share = remaining / pending.size();
            for (String name : pending) {
                granted.put(name, Math.max(0, share));
            }
        }
        return granted;
    }
}
