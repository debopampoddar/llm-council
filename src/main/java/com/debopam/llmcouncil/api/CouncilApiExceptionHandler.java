package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.application.CouncilRunStateException;
import com.debopam.llmcouncil.config.user.UserConfigDocumentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Shared HTTP mapping for the exception types more than one controller can raise.
 *
 * <p>These handlers previously lived on individual controllers, which meant the
 * mapping depended on which controller happened to be entered: an unknown id was
 * a 404 from the council and chat controllers but a 500 from the configuration
 * and advisor controllers, and the same {@code UserConfigDocumentException} block
 * was written out twice. A controller-local {@code @ExceptionHandler} still wins
 * over this advice, so a controller that needs a different status or response
 * body keeps declaring its own.
 *
 * <p>{@link IllegalStateException} is deliberately <b>not</b> mapped here. Only
 * {@link CouncilRunStateException}, which extends it, means "the caller asked for
 * something the current state forbids". A bare {@code IllegalStateException} is a
 * defect, and reporting a defect as 409 Conflict would tell the caller to retry
 * something that will never succeed.
 */
@RestControllerAdvice
public class CouncilApiExceptionHandler {

    /**
     * Unknown session, chat, or profile identifiers.
     *
     * @param ex the lookup failure
     * @return 404 Not Found with the failure message
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /**
     * Invalid profile, depth mode, or policy combinations supplied by the caller.
     *
     * @param ex the rejected argument
     * @return 400 Bad Request with the failure message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    /**
     * A run or chat is in a state that forbids the requested transition — a
     * session is single-use, and a chat mid-run cannot be deleted.
     *
     * @param ex the state conflict
     * @return 409 Conflict with the failure message
     */
    @ExceptionHandler(CouncilRunStateException.class)
    public ResponseEntity<String> handleConflict(CouncilRunStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /**
     * A configuration document that could not be read, parsed, or written.
     *
     * @param ex the document failure, carrying the issues to report
     * @return 400 Bad Request with the validation report
     */
    @ExceptionHandler(UserConfigDocumentException.class)
    public ResponseEntity<ValidationReportResponse> handleUnreadableDocument(
            UserConfigDocumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                             .body(ValidationReportResponse.of(ex.issues(), false));
    }
}
