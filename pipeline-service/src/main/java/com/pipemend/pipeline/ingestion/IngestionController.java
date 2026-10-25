package com.pipemend.pipeline.ingestion;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/ingestions")
public class IngestionController {

    private final IngestionService ingestions;
    private final BatchQueryService queries;

    public IngestionController(IngestionService ingestions, BatchQueryService queries) {
        this.ingestions = ingestions;
        this.queries = queries;
    }

    @Operation(
            summary = "Upload a CSV file and start its ingestion",
            description = "Registers the file as a batch and processes it in the background (FR-01).")
    @ApiResponse(
            responseCode = "202",
            description = "Batch registered; follow it at the Location header",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                                {"batchId": "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f", "status": "RUNNING",
                                 "sourceFilename": "clean-1k.csv", "sourceType": "CSV",
                                 "sourceChecksum": "3f9a...c01", "receivedAt": "2026-10-05T14:03:11Z"}
                                """)))
    @ApiResponse(
            responseCode = "400",
            description = "EMPTY_FILE or MISSING_FILE",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                {"type": "https://pipemend.dev/problems/empty-file", "title": "Empty file",
                                 "status": 400, "detail": "The file is empty or has a header and no records.",
                                 "instance": "/api/v1/ingestions", "code": "EMPTY_FILE",
                                 "requestId": "5f1c2b3a-0d4e-4f6a-9b8c-7d6e5f4a3b2c"}
                                """)))
    @ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE")
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE: only .csv until FR-02")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResponse> ingest(@RequestPart("file") MultipartFile file) throws IOException {
        IngestionResponse response = ingestions.ingest(file.getOriginalFilename(), file);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/ingestions/" + response.batchId()))
                .body(response);
    }

    @Operation(
            summary = "Status and counters of a batch",
            description = "While the batch runs, the counters reflect the committed chunks (FR-14).")
    @ApiResponse(responseCode = "200", description = "The batch")
    @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: the id is not a UUID")
    @ApiResponse(responseCode = "404", description = "BATCH_NOT_FOUND")
    @GetMapping("/{batchId}")
    public BatchResponse batch(@PathVariable UUID batchId) {
        return queries.find(batchId);
    }

    @Operation(summary = "Batches, newest first", description = "Paginated; optionally filtered by status (FR-14).")
    @ApiResponse(responseCode = "200", description = "A page of batches")
    @ApiResponse(
            responseCode = "400",
            description = "INVALID_PARAMETER: unknown status, page below 0 or size outside 1..200")
    @GetMapping
    public PageResponse<BatchResponse> batches(
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.list(status, page, size);
    }
}
