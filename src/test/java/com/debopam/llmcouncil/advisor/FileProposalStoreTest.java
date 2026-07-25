package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.config.user.SecretScanner;
import com.debopam.llmcouncil.config.user.UserConfigCodec;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserModel;
import com.debopam.llmcouncil.config.user.UserConfigDocumentException;
import com.debopam.llmcouncil.config.user.UserConfigLoader;
import com.debopam.llmcouncil.persistence.AtomicFileWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file-backed proposal store, plus what is specific to being a file.
 *
 * <p>The contract cases come from {@link ProposalStoreContractTest}. What is
 * added here is everything that only makes sense because a proposal is a file
 * sitting next to a configuration file somebody may copy, rename, or read.
 */
class FileProposalStoreTest extends ProposalStoreContractTest {

    @TempDir
    private Path directory;

    private FileProposalStore store;

    @BeforeEach
    void createStore() {
        SecretScanner scanner = new SecretScanner();
        UserConfigLoader loader = new UserConfigLoader(
                scanner, directory.resolve("council-user.yml").toString(), directory.toString());
        store = new FileProposalStore(loader, new UserConfigCodec(scanner), new AtomicFileWriter());
    }

    @Override
    protected ProposalStore store() {
        return store;
    }

    @Test
    void theProposalSitsBesideTheConfigurationItWouldBecome() {
        store.save(proposal("advisor-first", CouncilRequirement.Rigor.BALANCED));

        assertTrue(Files.exists(directory.resolve(FileProposalStore.PROPOSAL_FILE_NAME)),
                   "a user who moved their configuration should find the proposal with it");
    }

    @Test
    void savingAProposalDoesNotWriteConfiguration() {
        store.save(proposal("advisor-first", CouncilRequirement.Rigor.BALANCED));

        assertFalse(Files.exists(directory.resolve("council-user.yml")),
                    "saving a proposal must not touch the overlay — nothing is applied");
    }

    @Test
    void theFileSaysItIsNotConfigurationInItsFirstLine() throws IOException {
        store.save(proposal("advisor-first", CouncilRequirement.Rigor.BALANCED));

        String written = Files.readString(directory.resolve(FileProposalStore.PROPOSAL_FILE_NAME),
                                          StandardCharsets.UTF_8);

        assertTrue(written.contains("NOT active configuration"),
                   "somebody opening this file should not have to infer what it is");
        assertTrue(written.contains("kind: " + ProposalEnvelope.KIND),
                   "and the marker must be structural, not just a comment: " + written);
    }

    @Test
    void discardingLeavesNothingBehind() throws IOException {
        store.save(proposal("advisor-first", CouncilRequirement.Rigor.BALANCED));
        store.save(proposal("advisor-second", CouncilRequirement.Rigor.RIGOROUS));
        store.discard();

        try (var entries = Files.list(directory)) {
            List<Path> remaining = entries.toList();
            assertTrue(remaining.isEmpty(),
                       "a discard that left a copy next to the file it deleted would not be a "
                       + "discard: " + remaining);
        }
    }

    @Test
    void savingTheOverlayWouldHaveKeptABackup() throws IOException {
        // Control for the test above: the backup behaviour exists and is chosen
        // per file, rather than being absent everywhere.
        Path overlay = directory.resolve("council-user.yml");
        AtomicFileWriter writer = new AtomicFileWriter();
        writer.write(overlay, "version: 1\n", true);
        writer.write(overlay, "version: 1\n# changed\n", true);

        assertTrue(Files.exists(AtomicFileWriter.backupOf(overlay)),
                   "the configuration overlay keeps one previous version");
    }

    @Test
    void aFileWithoutTheMarkerIsNotAProposal() throws IOException {
        // Someone renames their overlay to the proposal name. It parses; it is
        // not a proposal, and treating it as one would defeat the marker in the
        // other direction.
        Files.writeString(directory.resolve(FileProposalStore.PROPOSAL_FILE_NAME),
                          "kind: something-else\nsavedAt: \"2026-07-24T09:00:00Z\"\n"
                          + "requirement: null\nrationale: []\ndocument:\n  version: 1\n",
                          StandardCharsets.UTF_8);

        assertTrue(store.load().isEmpty(), "a file that is not a proposal reports as no proposal");
    }

    @Test
    void anUnreadableProposalIsReportedAsAbsentRatherThanThrowing() throws IOException {
        Files.writeString(directory.resolve(FileProposalStore.PROPOSAL_FILE_NAME),
                          "this: [is not: valid yaml", StandardCharsets.UTF_8);

        assertTrue(store.load().isEmpty(),
                   "a proposal is a convenience; an unreadable one must not fail the request "
                   + "that asked about it");
    }

    @Test
    void aCredentialIsRefusedBeforeItReachesTheDisk() {
        ProposalEnvelope withSecret = new ProposalEnvelope(
                ProposalEnvelope.KIND, "2026-07-24T09:00:00Z", CouncilRequirement.defaults(),
                List.of(),
                new UserConfigDocument(
                        UserConfigDocument.SUPPORTED_VERSION,
                        // Smuggled through a field that legitimately accepts free
                        // text, which is exactly what the value scan is for.
                        List.of(new UserModel("advisor-leak", "ollama",
                                              "sk-abcdefghijklmnopqrstuvwxyz", 1200, 0.3, 240,
                                              null, "MEMBER", "PROPOSER", "canary",
                                              null, null, null, null)),
                        Map.of(), Map.of(), Map.of(), null));

        assertThrows(UserConfigDocumentException.class, () -> store.save(withSecret));
        assertFalse(Files.exists(directory.resolve(FileProposalStore.PROPOSAL_FILE_NAME)),
                    "the refusal must happen before anything durable is written");
    }

    @Test
    void anInnocentProposalIsNotRefused() {
        // Control: the refusal above is the credential, not saving being broken.
        store.save(proposal("advisor-first", CouncilRequirement.Rigor.BALANCED));

        assertTrue(store.load().isPresent());
    }

    @Test
    void aProposalSurvivesBeingWrittenTwiceConcurrentlyEnoughToStayParseable() {
        // Not a concurrency test: the point is that the atomic write leaves a
        // whole file, never a truncated one that would parse as something else.
        for (int i = 0; i < 20; i++) {
            store.save(proposal("advisor-" + i, CouncilRequirement.Rigor.BALANCED));
            ProposalEnvelope loaded = store.load().orElseThrow();
            assertTrue(loaded.valid(), "iteration " + i + " left an unusable file");
        }
    }

    @Test
    void theRequirementSurvivesAsChoicesRatherThanText() {
        store.save(proposal("advisor-first", CouncilRequirement.Rigor.RIGOROUS));

        CouncilRequirement requirement = store.load().orElseThrow().requirement();

        // The proposal stores the requirement so a resumed proposal can say
        // whether re-synthesising today would differ. That only works if it
        // survives as the closed choices it was, not as prose.
        assertTrue(requirement.adversarialEmphasis());
        assertTrue(requirement.localOnly());
        assertTrue(Set.of(CouncilRequirement.Domain.CODE).equals(requirement.domains()));
    }

    @Test
    void savedAtIsReadableInTheFileRatherThanAnEpochNumber() throws IOException {
        store.save(ProposalEnvelope.of(CouncilRequirement.defaults(),
                                       new SynthesisResult(UserConfigDocument.empty(),
                                                           AdvisorIds.PROFILE, List.of(), List.of()),
                                       Instant.parse("2026-07-24T09:00:00Z")));

        assertTrue(Files.readString(directory.resolve(FileProposalStore.PROPOSAL_FILE_NAME))
                        .contains("2026-07-24T09:00:00Z"),
                   "somebody reading the file should be able to tell when they saved it");
    }
}
