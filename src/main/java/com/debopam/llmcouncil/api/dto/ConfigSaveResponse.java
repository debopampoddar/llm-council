package com.debopam.llmcouncil.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The outcome of asking to save a configuration.
 *
 * <p>{@code written} is reported separately from {@code validation.valid}
 * because they can differ: a configuration can be valid and still fail to be
 * written if the file cannot be created. Collapsing the two would let a user
 * read "valid" as "saved".
 *
 * <p>{@code restartRequired} is true whenever anything was written, and is not a
 * hedge. The running catalog is pinned at boot and every in-flight council run
 * holds its own snapshot of it; nothing on this path swaps either. A UI that
 * implied the change was already live would be describing a different feature.
 *
 * @param written         whether the overlay file was replaced
 * @param restartRequired whether the saved configuration is waiting on a restart
 * @param path            the file that was written, or would have been
 * @param backupPath      the previous contents, kept alongside; null when there
 *                        was no previous file or nothing was written
 * @param validation      what validating the submitted configuration found
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigSaveResponse(
        boolean written,
        boolean restartRequired,
        String path,
        String backupPath,
        ValidationReportResponse validation
) {

    /**
     * The configuration was saved.
     *
     * @param path       the file written
     * @param backupPath the previous contents, or null when there were none
     * @param validation the report, which may still carry warnings
     * @return a written outcome
     */
    public static ConfigSaveResponse written(String path, String backupPath,
                                             ValidationReportResponse validation) {
        return new ConfigSaveResponse(true, true, path, backupPath, validation);
    }

    /**
     * Nothing was written.
     *
     * @param path       the file that would have been written, or null when the
     *                   configured location could not be resolved
     * @param validation why not
     * @return an unwritten outcome
     */
    public static ConfigSaveResponse refused(String path, ValidationReportResponse validation) {
        return new ConfigSaveResponse(false, false, path, null, validation);
    }
}
