package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse;
import com.debopam.llmcouncil.application.ConfigSchemaService;
import com.debopam.llmcouncil.config.user.ConfigLimits;
import com.debopam.llmcouncil.config.user.StageOptionSpec;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The schema is generated, and generation is what these tests protect.
 *
 * <p>A hand-written schema passes every shape assertion on the day it is written
 * and then rots: an option is added to the validator, the form never offers it,
 * and nothing fails. So the assertions here are all comparisons against the
 * sources the schema claims to be derived from — {@link StageOptionSpec}, the
 * {@link UserConfigDocument} records, {@link ConfigLimits} — plus literal counts,
 * which are what actually break when someone adds a field and stops there.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigSchemaTest {

    /**
     * The number of tunable stage options, as of §2.4 of the plan.
     *
     * <p>Deliberately a literal. Comparing the response against
     * {@code StageOptionSpec.all().size()} proves the two agree but not that
     * either is complete; a new option added to the spec and forgotten everywhere
     * else would keep both sides equal. This number failing is the reminder to
     * look at the UI.
     */
    private static final int EXPECTED_STAGE_OPTIONS = 14;

    /** Entities the overlay can carry. A new one must be described here. */
    private static final int EXPECTED_ENTITIES = 6;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigSchemaService schemaService;

    @Autowired
    private com.debopam.llmcouncil.config.user.SecretScanner secretScanner;

    @Test
    void servesTheSchema() throws Exception {
        mockMvc.perform(get("/api/council/config/schema"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.version").value(UserConfigDocument.SUPPORTED_VERSION))
               .andExpect(jsonPath("$.entities").isArray())
               .andExpect(jsonPath("$.stageOptions").isArray())
               .andExpect(jsonPath("$.providers").isArray())
               .andExpect(jsonPath("$.locked").isArray());
    }

    @Test
    void everyStageOptionSpecAppearsInTheSchema() {
        Set<String> published = schemaService.schema().stageOptions().stream()
                .map(option -> option.stage() + "." + option.key())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (StageOptionSpec spec : StageOptionSpec.all()) {
            assertTrue(published.contains(spec.stage().name() + "." + spec.key()),
                       "stage option " + spec.stage() + "." + spec.key()
                       + " is tunable but the UI is never told about it");
        }
        assertEquals(StageOptionSpec.all().size(), published.size(),
                     "the schema published a stage option the validator does not permit");
    }

    @Test
    void theStageOptionCountIsPinned() {
        // Fails when an option is added to StageOptionSpec. That is the point:
        // adding one without deciding how it renders is the mistake to catch.
        assertEquals(EXPECTED_STAGE_OPTIONS, schemaService.schema().stageOptions().size());
        assertEquals(EXPECTED_STAGE_OPTIONS, StageOptionSpec.all().size());
    }

    @Test
    void everyStageOptionCarriesItsClampAndItsDefault() {
        for (ConfigSchemaResponse.StageOptionSchema option : schemaService.schema().stageOptions()) {
            StageOptionSpec spec = StageOptionSpec
                    .find(com.debopam.llmcouncil.orchestration.StageType.valueOf(option.stage()),
                          option.key())
                    .orElseThrow();

            assertEquals(spec.min(), option.min(), option.key() + " lower bound");
            assertEquals(spec.max(), option.max(), option.key() + " upper bound");
            assertEquals(spec.defaultValue(), option.defaultValue(), option.key() + " default");
            assertEquals(spec.allowedValues(), option.allowedValues(), option.key() + " enum values");
            assertEquals(spec.pattern(), option.pattern(), option.key() + " pattern");
            assertEquals(spec.integrityReducing(), option.integrityReducing(),
                         option.key() + " integrity flag");
            assertNotNull(option.description(), option.key() + " has no help text");
        }
    }

    @Test
    void integrityReducingOptionsAreFlaggedSoTheFormCanCautionAboutThem() {
        List<String> flagged = schemaService.schema().stageOptions().stream()
                .filter(ConfigSchemaResponse.StageOptionSchema::integrityReducing)
                .map(ConfigSchemaResponse.StageOptionSchema::key)
                .toList();

        // Positive control for the assertion above: if nothing were ever flagged,
        // "the flag matches the spec" would hold vacuously.
        assertTrue(flagged.contains("sycophancy-threshold"),
                   "raising the sycophancy threshold suppresses warnings rather than the behaviour, "
                   + "and must reach the UI marked as such");
        assertTrue(flagged.contains("preserve-dissent"),
                   "turning dissent preservation off makes an answer read more confident than the "
                   + "council was, and must reach the UI marked as such");
    }

    @ParameterizedTest
    @CsvSource({
            "model,    com.debopam.llmcouncil.config.user.UserConfigDocument$UserModel",
            "policy,   com.debopam.llmcouncil.config.user.UserConfigDocument$UserPolicy",
            "profile,  com.debopam.llmcouncil.config.user.UserConfigDocument$UserProfile",
            "protocol, com.debopam.llmcouncil.config.user.UserConfigDocument$UserProtocol",
            "runtime,  com.debopam.llmcouncil.config.user.UserConfigDocument$UserRuntime",
            "retention,com.debopam.llmcouncil.config.user.UserConfigDocument$UserRetention"
    })
    void everyOverlayFieldIsDescribed(String entityName, String recordClassName) throws Exception {
        ConfigSchemaResponse.EntitySchema entity = entity(entityName);
        Set<String> described = entity.fields().stream()
                .map(ConfigSchemaResponse.FieldSchema::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (RecordComponent component : Class.forName(recordClassName).getRecordComponents()) {
            assertTrue(described.contains(component.getName()),
                       entityName + "." + component.getName()
                       + " can be written to the overlay but the schema does not describe it");
        }

        // Anything extra must be the map key, which is not a record component
        // because it is how the entity is addressed rather than a field of it.
        Set<String> extra = new LinkedHashSet<>(described);
        for (RecordComponent component : Class.forName(recordClassName).getRecordComponents()) {
            extra.remove(component.getName());
        }
        extra.remove("id");
        assertTrue(extra.isEmpty(), entityName + " describes fields the overlay cannot carry: " + extra);
    }

    @Test
    void theEntityCountIsPinned() {
        assertEquals(EXPECTED_ENTITIES, schemaService.schema().entities().size());
    }

    @Test
    void clampsComeFromTheValidatorsOwnBounds() {
        ConfigSchemaResponse.FieldSchema temperature = field("model", "temperature");
        assertEquals(ConfigLimits.MIN_TEMPERATURE, temperature.min());
        assertEquals(ConfigLimits.MAX_TEMPERATURE, temperature.max());

        ConfigSchemaResponse.FieldSchema timeout = field("model", "timeoutSeconds");
        assertEquals((double) ConfigLimits.MIN_TIMEOUT_SECONDS, timeout.min());
        assertEquals((double) ConfigLimits.MAX_TIMEOUT_SECONDS, timeout.max());

        ConfigSchemaResponse.FieldSchema members = field("policy", "memberModelIds");
        assertEquals((double) ConfigLimits.MAX_MEMBERS, members.max());

        ConfigSchemaResponse.FieldSchema sessions = field("retention", "maxSessions");
        assertEquals((double) ConfigLimits.MIN_MAX_SESSIONS, sessions.min());

        assertEquals(ConfigLimits.sortedProviders(), schemaService.schema().providers());
        assertEquals(ConfigLimits.sortedProviders(), field("model", "provider").allowedValues());
    }

    @Test
    void derivableProtocolsComeFromTheShippedCatalog() {
        List<String> derivable = field("protocol", "derivedFrom").allowedValues();

        assertTrue(derivable.contains("rigorous"));
        assertTrue(derivable.contains("balanced"));
        assertTrue(derivable.contains("quick"));
    }

    @Test
    void theSchemaNamesWhatItRefusesToOffer() {
        List<String> locked = schemaService.schema().locked().stream()
                .map(ConfigSchemaResponse.LockedRule::name)
                .toList();

        // A missing control reads as an oversight unless the absence is stated.
        assertTrue(locked.contains("credentials"));
        assertTrue(locked.contains("orderedStages"));
        assertTrue(locked.contains("testOnly"));
        assertTrue(locked.contains("allowMockFallback"));
    }

    @Test
    void noFieldInTheSchemaCouldHoldACredential() {
        // Checked with the production scanner rather than a second regex, so the
        // rule the schema is held to is exactly the rule the overlay file is
        // held to. A field the scanner would reject on load must never be
        // offered by the form that writes that file.
        for (ConfigSchemaResponse.EntitySchema entity : schemaService.schema().entities()) {
            for (ConfigSchemaResponse.FieldSchema fieldSchema : entity.fields()) {
                assertTrue(secretScanner.scan("  " + fieldSchema.name() + ": value\n").isEmpty(),
                           "the overlay must have no field a key could be typed into, but "
                           + entity.name() + "." + fieldSchema.name() + " is one the loader "
                           + "would refuse");
            }
        }
    }

    @Test
    void theCredentialCheckAboveCanActuallyFail() {
        // Positive control. Without it, a scanner that matched nothing at all
        // would make the previous test pass for the wrong reason.
        assertFalse(secretScanner.scan("  apiKey: value\n").isEmpty());
    }

    private ConfigSchemaResponse.EntitySchema entity(String name) {
        return schemaService.schema().entities().stream()
                .filter(entity -> entity.name().equals(name.trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no schema for entity " + name));
    }

    private ConfigSchemaResponse.FieldSchema field(String entityName, String fieldName) {
        return entity(entityName).fields().stream()
                .filter(field -> field.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no schema for " + entityName + "." + fieldName));
    }
}
