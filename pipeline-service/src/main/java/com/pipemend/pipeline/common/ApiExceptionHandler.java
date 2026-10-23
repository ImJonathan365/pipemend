package com.pipemend.pipeline.common;

import com.pipemend.pipeline.ingestion.DuplicateIngestionException;
import com.pipemend.pipeline.ingestion.EmptyFileException;
import com.pipemend.pipeline.ingestion.UnsupportedFileTypeException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Every API error as application/problem+json with the stable codes of docs/06 B.1 (NFR-20); never a stacktrace. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(UnsupportedFileTypeException.class)
    ResponseEntity<ProblemDetail> unsupported(UnsupportedFileTypeException e, HttpServletRequest request) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type", e, request);
    }

    @ExceptionHandler(DuplicateIngestionException.class)
    ResponseEntity<ProblemDetail> duplicate(DuplicateIngestionException e, HttpServletRequest request) {
        ProblemDetail problem =
                problem(HttpStatus.CONFLICT, "DUPLICATE_INGESTION", "Duplicate ingestion", e.getMessage(), request);
        problem.setProperty("existingBatchId", e.existingBatchId());
        problem.setProperty("existingStatus", e.existingStatus());
        return respond(problem);
    }

    @ExceptionHandler(EmptyFileException.class)
    ResponseEntity<ProblemDetail> empty(EmptyFileException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Empty file", e, request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> missing(MissingServletRequestPartException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_FILE", "Missing file", e, request);
    }

    // Without this mapping Spring answers a generic error page (AC-01.4).
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "File too large", e, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        // Spring's own errors (404, 405, wrong content type...) keep their status and body.
        if (e instanceof ErrorResponse spring) {
            ProblemDetail problem = spring.getBody();
            problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
            return respond(problem);
        }
        log.error("Unexpected error on {}", request.getRequestURI(), e);
        ProblemDetail problem = problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal error",
                "The request could not be completed.",
                request);
        return respond(problem);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String title, Exception e, HttpServletRequest request) {
        return respond(problem(status, code, title, detail(e), request));
    }

    private static ProblemDetail problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(
                "https://pipemend.dev/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return problem;
    }

    private static String detail(Exception e) {
        return switch (e) {
            case MissingServletRequestPartException missing -> "The multipart part 'file' is required.";
            case MaxUploadSizeExceededException tooLarge -> "The file exceeds the maximum upload size.";
            default -> e.getMessage();
        };
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
