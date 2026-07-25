package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.CatalogDiffResponse;
import com.debopam.llmcouncil.api.dto.CatalogResponse;
import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.ConfigOrigin;
import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.user.CatalogMerger;
import com.debopam.llmcouncil.config.user.IntegrityAssessment;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.config.user.UserConfigLoader;
import com.debopam.llmcouncil.config.user.UserConfigValidator;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.UnavailableModelClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads, validates, and previews the user configuration overlay.
 *
 * <p>Everything here is a pure function of a submitted document and the shipped
 * catalog. Nothing on this class writes: saving is a separate, deliberate step,
 * and keeping the two apart is what lets a UI validate on every keystroke
 * without touching disk.
 *
 * <p>Validation resolves against {@link CouncilCatalogHolder#builtIn()} rather
 * than the running catalog. Against the running catalog, a draft that dropped one
 * of its own models would still resolve every policy that referenced it — the
 * model is in the active catalog because the <em>current</em> overlay put it
 * there — and the configuration would break at the next restart instead of in the
 * form.
 */
@Service
public class ConfigDraftService {

    private final UserConfigLoader loader;
    private final UserConfigValidator validator;
    private final CouncilCatalogHolder catalogHolder;

    /**
     * @param loader        reads the overlay currently on disk
     * @param validator     enforces the capability boundary
     * @param catalogHolder holds the shipped and active configuration snapshots
     */
    public ConfigDraftService(UserConfigLoader loader,
                              UserConfigValidator validator,
                              CouncilCatalogHolder catalogHolder) {
        this.loader = loader;
        this.validator = validator;
        this.catalogHolder = catalogHolder;
    }

    /**
     * Read the overlay as it currently exists on disk.
     *
     * <p>An absent file is not an error: it is the normal state of an
     * installation running shipped configuration, and an empty document is the
     * honest description of it.
     *
     * @return the current draft, or an empty document when there is no file
     */
    public UserConfigDocument draft() {
        return loader.load().document();
    }

    /**
     * Check a proposed configuration without saving it.
     *
     * @param document the proposed overlay
     * @return every problem found, and whether it could be saved
     */
    public ValidationReportResponse validate(UserConfigDocument document) {
        UserConfigValidator.ValidationReport report =
                validator.validate(document, catalogHolder.builtIn());
        return ValidationReportResponse.of(report.issues(), reducesIntegrity(report.sanitised()));
    }

    /**
     * Show what a proposed configuration would change.
     *
     * @param document the proposed overlay
     * @return the entity-level diff, the resulting profile list, and the
     *         validation report the diff was derived under
     */
    public CatalogDiffResponse preview(UserConfigDocument document) {
        CouncilCatalog builtIn = catalogHolder.builtIn();
        CouncilCatalog active = catalogHolder.get();

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn);

        // Merged from the sanitised document, so the preview shows what would
        // actually apply rather than what was typed. The dropped entities are
        // reported alongside, in the validation report travelling with this diff.
        CouncilCatalog proposed = new CatalogMerger(ConfigDraftService::previewClient)
                .merge(builtIn, report.sanitised(), report.issues(), active.generation() + 1);

        List<CatalogDiffResponse.EntityChange> changes = new ArrayList<>();
        diff("model", proposed, builtIn.modelRegistry().modelIds(),
             proposed.modelRegistry().modelIds(), active.modelRegistry().modelIds(), changes);
        diff("policy", proposed, builtIn.policies().keySet(),
             proposed.policies().keySet(), active.policies().keySet(), changes);
        diff("profile", proposed, builtIn.profiles().keySet(),
             proposed.profiles().keySet(), active.profiles().keySet(), changes);
        diff("protocol", proposed, builtIn.protocols().keySet(),
             proposed.protocols().keySet(), active.protocols().keySet(), changes);

        List<CatalogResponse.ProfileSummary> profiles = proposed.profiles().entrySet().stream()
                .map(entry -> CatalogResponse.ProfileSummary.from(
                        entry.getKey(), entry.getValue(),
                        proposed.originOf("profile", entry.getKey())))
                .toList();

        return new CatalogDiffResponse(
                active.generation(),
                List.copyOf(changes),
                profiles,
                ValidationReportResponse.of(report.issues(), reducesIntegrity(report.sanitised())));
    }

    // ── Diff ────────────────────────────────────────────────────────────

    /**
     * Compare one entity type across the three snapshots that matter.
     *
     * <p>Three, not two, because "added" and "removed" are questions about
     * different baselines. What the configuration <em>contributes</em> is measured
     * against the shipped catalog: that is what a user is deciding about, and it
     * does not change depending on what happens to be running. What would be
     * <em>lost</em> is measured against the running catalog, because an entity can
     * only be lost if the overlay in force today is what put it there. A built-in
     * can never be lost — it can be shadowed, or not selected, but not deleted —
     * so shipped ids are excluded from the removal set outright.
     *
     * <p>Untouched built-ins are omitted entirely. A diff that listed all fifteen
     * shipped models next to the two a user changed would bury its own answer.
     */
    private void diff(String type, CouncilCatalog proposedCatalog, Set<String> builtInIds,
                      Set<String> proposedIds, Set<String> activeIds,
                      List<CatalogDiffResponse.EntityChange> changes) {
        for (String id : proposedIds) {
            ConfigOrigin origin = proposedCatalog.originOf(type, id);
            if (origin == ConfigOrigin.BUILT_IN) {
                continue;
            }
            if (origin == ConfigOrigin.USER_OVERRIDE) {
                changes.add(new CatalogDiffResponse.EntityChange(type, id,
                        CatalogDiffResponse.Change.OVERRIDDEN,
                        "Shadows the built-in " + type + " of the same id. The built-in is not "
                        + "deleted and returns if this entry is removed."));
            } else {
                changes.add(new CatalogDiffResponse.EntityChange(type, id,
                        CatalogDiffResponse.Change.ADDED,
                        "Defined by this configuration. There is no built-in " + type
                        + " with this id."));
            }
        }

        Set<String> lost = new LinkedHashSet<>(activeIds);
        lost.removeAll(proposedIds);
        lost.removeAll(builtInIds);
        for (String id : lost) {
            changes.add(new CatalogDiffResponse.EntityChange(type, id,
                    CatalogDiffResponse.Change.REMOVED,
                    "Currently defined by your configuration and absent from this one. Anything "
                    + "still referencing it stops resolving."));
        }
    }

    // ── Integrity ───────────────────────────────────────────────────────

    /**
     * Whether this configuration weakens an anti-sycophancy guarantee.
     *
     * <p>Asked of the option's own spec rather than re-derived here, so that the
     * threshold above which a sycophancy setting counts as suppression is written
     * down exactly once.
     *
     * @param sanitised the document as it would apply
     * @return {@code true} when at least one option weakens a guarantee
     */
    private boolean reducesIntegrity(UserConfigDocument sanitised) {
        return sanitised.protocols().values().stream()
                        .anyMatch(protocol -> IntegrityAssessment.reducedIn(protocol.stageOptions()));
    }

    /**
     * A stand-in client for previewing.
     *
     * <p>A preview must not construct provider clients. Building one is not free
     * of consequence — it is where credential detection and connection setup
     * happen — and nothing about the diff depends on whether a model would be
     * callable. Provider availability is a separate question with a separate
     * answer: {@code GET /api/council/catalog?include=providers}.
     *
     * @param profile the model being previewed
     * @return a client that explains it exists only for the preview
     */
    private static UnavailableModelClient previewClient(ModelProfile profile) {
        return new UnavailableModelClient(profile.id(),
                "this catalog was built to preview a configuration change and is never run against");
    }

    /**
     * Issues found while reading the overlay from disk, if any.
     *
     * @return problems with the file as it stands, empty when it is clean or absent
     */
    public List<ConfigIssue> draftIssues() {
        return loader.load().issues();
    }
}
