package com.debopam.llmcouncil.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A machine-readable description of what a user configuration overlay may
 * contain.
 *
 * <p>This exists so the configuration UI never states a rule of its own. Ranges
 * become {@code min}/{@code max} on number inputs, enumerations become selects,
 * and help text comes from the same place the validator's error messages come
 * from. A form that hard-coded any of it would drift from the validator within a
 * release, and the drift is silent in both directions: a field that refuses a
 * value the API accepts, or offers one it refuses.
 *
 * <p>The response describes the overlay's <em>shape</em>, not its contents. What
 * models and policies currently exist is a catalog question, answered by
 * {@code GET /api/council/catalog}.
 *
 * <p>Nothing here can name a credential. The overlay has no field that accepts
 * one, so the schema generated from it has none to describe.
 *
 * @param version      overlay schema version this describes
 * @param entities     one entry per editable entity type
 * @param stageOptions every tunable protocol stage option
 * @param providers    providers a user may bind a model to
 * @param depthModes   depth modes a profile may map to a policy
 * @param stages       protocol stage names, for display only — stage order is
 *                     not user-editable
 * @param locked       rules the form must not offer to change, with the reason
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigSchemaResponse(
        int version,
        List<EntitySchema> entities,
        List<StageOptionSchema> stageOptions,
        List<String> providers,
        List<String> depthModes,
        List<String> stages,
        List<LockedRule> locked
) {

    /**
     * How a field should be rendered and what it is checked against.
     *
     * <p>Wider than a JSON type on purpose: {@code ID} and {@code ID_LIST} tell a
     * form to offer a picker of existing entities rather than a free-text box,
     * which is the difference between a typo caught at validation and one caught
     * at save.
     */
    public enum FieldType {
        /** Free text. */
        STRING,
        /** A whole number. */
        INT,
        /** A fractional number. */
        DOUBLE,
        /** True or false. */
        BOOLEAN,
        /** One of {@link FieldSchema#allowedValues()}. */
        ENUM,
        /** A slug that names an entity of this or another type. */
        ID,
        /** An ordered list of entity ids. */
        ID_LIST,
        /** Depth mode to policy id. */
        DEPTH_POLICY_MAP,
        /** Stage name to option name to value; see {@link #stageOptions()}. */
        STAGE_OPTION_MAP,
        /** An absolute filesystem path. */
        ABSOLUTE_PATH,
        /** A nested entity described by its own {@link EntitySchema}. */
        NESTED
    }

    /**
     * One editable entity type.
     *
     * @param name        entity name as written in the overlay
     * @param keyedById   whether instances are keyed by id in a map, rather than
     *                    listed
     * @param description one line explaining what the entity is for
     * @param fields      the fields it accepts
     */
    public record EntitySchema(
            String name,
            boolean keyedById,
            String description,
            List<FieldSchema> fields
    ) {}

    /**
     * One field of an entity.
     *
     * @param name          field name as written in the overlay
     * @param type          how to render and check it
     * @param required      whether the overlay must supply it
     * @param min           inclusive lower bound, or null when unbounded
     * @param max           inclusive upper bound, or null when unbounded
     * @param maxLength     maximum text length, or null when unbounded
     * @param pattern       regular expression the value must match, or null
     * @param allowedValues permitted values for {@link FieldType#ENUM}, else empty
     * @param nested        the entity describing this field's shape for
     *                      {@link FieldType#NESTED}, else null
     * @param help          one line explaining the field, shown beside the input
     */
    public record FieldSchema(
            String name,
            FieldType type,
            boolean required,
            Double min,
            Double max,
            Integer maxLength,
            String pattern,
            List<String> allowedValues,
            String nested,
            String help
    ) {}

    /**
     * One tunable protocol stage option.
     *
     * <p>Generated from the same table the validator rejects out-of-range values
     * with, so a new option is offered by the UI the moment it is permitted, and
     * never before.
     *
     * @param stage             the stage the option belongs to
     * @param key               the option name as written in configuration
     * @param type              the value type
     * @param min               inclusive lower bound, or null when unbounded
     * @param max               inclusive upper bound, or null when unbounded
     * @param defaultValue      what the stage uses when the option is absent
     * @param allowedValues     permitted values for an enumerated option, else empty
     * @param pattern           regular expression for a text option, else null
     * @param integrityReducing whether setting this weakens an anti-sycophancy
     *                          guarantee, and must therefore be rendered with a
     *                          visible caution rather than as an ordinary control
     * @param description       one line explaining what the option does
     */
    public record StageOptionSchema(
            String stage,
            String key,
            FieldType type,
            Double min,
            Double max,
            Object defaultValue,
            List<String> allowedValues,
            String pattern,
            boolean integrityReducing,
            String description
    ) {}

    /**
     * Something the overlay deliberately cannot express.
     *
     * <p>Published so the UI can say why a control is absent. "There is no field
     * for your API key" is a very different message from a form that simply
     * happens not to have one.
     *
     * @param name   what a user might look for
     * @param reason why it is not editable, and where it lives instead
     */
    public record LockedRule(String name, String reason) {}
}
