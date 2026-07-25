package com.debopam.llmcouncil.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a file without ever leaving a partial one in its place.
 *
 * <p>Content goes to a temporary file in the destination directory and is moved
 * into place. A crash mid-write leaves the previous file intact rather than a
 * truncated one — and a truncated configuration file is worse than no
 * configuration file, because it parses.
 *
 * <p>Extracted from {@code ConfigWriteService} when the requirement advisor
 * gained a second file to write. Two copies of this would have been two chances
 * to get the ordering wrong, and the ordering is the whole point: the backup is
 * taken <em>before</em> the move, so it holds the version being replaced rather
 * than the one replacing it.
 */
@Component
public class AtomicFileWriter {

    private static final Logger log = LoggerFactory.getLogger(AtomicFileWriter.class);

    /** Suffix for the single retained previous version. */
    public static final String BACKUP_SUFFIX = ".bak";

    /**
     * Write content into place atomically.
     *
     * @param path       the destination
     * @param content    what to write
     * @param keepBackup whether to keep one previous version alongside it. The
     *                   user's configuration wants this — it may have been
     *                   hand-written and a UI that overwrote it with no way back
     *                   would be a bad trade. A file whose whole purpose is to be
     *                   discardable does not: leaving a copy behind after an
     *                   explicit discard would not be a discard.
     * @return the backup that was kept, or null when none was
     * @throws IOException when the directory or the file cannot be written
     */
    public Path write(Path path, String content, boolean keepBackup) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        Files.createDirectories(directory);

        // Same directory as the destination: ATOMIC_MOVE is only guaranteed
        // within one filesystem, and the system temp directory is often another.
        Path temporary = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
        Path backup = null;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);

            if (keepBackup && Files.exists(path)) {
                // Copied before the move, so the backup holds the version being
                // replaced rather than the one replacing it.
                backup = backupOf(path);
                Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                           StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                // Some filesystems cannot promise it. Say so rather than
                // pretending the guarantee held.
                log.warn("Atomic move is not supported at {}; {} was replaced without it. "
                         + "The previous version is at {}.", directory, path.getFileName(), backup);
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return backup;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The backup path for a destination.
     *
     * @param path the file being written
     * @return where its previous version is kept
     */
    public static Path backupOf(Path path) {
        return path.resolveSibling(path.getFileName() + BACKUP_SUFFIX);
    }
}
