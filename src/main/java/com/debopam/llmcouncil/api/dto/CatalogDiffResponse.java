package com.debopam.llmcouncil.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a proposed configuration would change.
 *
 * <p>Shown before saving, because the overlay is a merge and a merge is not
 * obvious from reading the document alone: an entity absent from the draft is
 * not removed if it is built in, and one present in the draft may be adding a
 * model or replacing a shipped one depending on its id.
 *
 * <p>Unchanged entities are omitted. A diff that listed every built-in model
 * alongside the two the user actually touched would bury the answer to the only
 * question being asked.
 *
 * <p>The diff describes what would apply, which is the <em>sanitised</em>
 * document — anything the validator would drop is already gone from it. That is
 * why the issues travel with the diff rather than being fetched separately: a
 * preview showing three changes from a draft that declared five is misleading
 * unless the two dropped entities are named in the same breath.
 *
 * @param currentGeneration the catalog generation this was compared against
 * @param changes           entities that would be added, replaced, or lost
 * @param profiles          the profiles a caller would be able to select after a
 *                          restart, including built-ins
 * @param validation        what validating the draft found
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogDiffResponse(
        long currentGeneration,
        List<EntityChange> changes,
        List<CatalogResponse.ProfileSummary> profiles,
        ValidationReportResponse validation
) {

    /** How a proposed configuration differs from the active one. */
    public enum Change {
        /** The entity does not exist today and would start to. */
        ADDED,

        /** A built-in entity of this id exists and would be shadowed by this one. */
        OVERRIDDEN,

        /**
         * The entity exists today because the current overlay defines it, and the
         * draft does not. It would stop existing.
         *
         * <p>Built-in entities never appear here: they can be shadowed, or simply
         * not selected, but a user cannot delete one.
         */
        REMOVED
    }

    /**
     * One entity that would change.
     *
     * @param type   entity type: {@code model}, {@code policy}, {@code profile},
     *               or {@code protocol}
     * @param id     the entity's id
     * @param change what would happen to it
     * @param detail one line of context, such as what a removal would orphan
     */
    public record EntityChange(String type, String id, Change change, String detail) {}
}
