package com.pipemend.pipeline.ingestion;

import com.pipemend.pipeline.batch.Inbox;
import com.pipemend.pipeline.batch.IngestionJobConfiguration;
import com.pipemend.pipeline.batch.InvalidFileFormatException;
import com.pipemend.pipeline.batch.RawRecordReader;
import com.pipemend.pipeline.persistence.IngestionBatchRepository;
import com.pipemend.pipeline.schema.SchemaDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

/**
 * Receives a file (FR-01): stores it in the inbox while hashing it, registers the batch and launches the job. Every
 * failure after the file is written removes its directory, so the inbox never keeps a file without a live batch.
 */
@Service
public class IngestionService {

    private static final int MAX_FILENAME_LENGTH = 255;

    private final Inbox inbox;
    private final IngestionBatchRepository batches;
    private final SchemaDefinition schema;
    private final JobOperator jobOperator;
    private final Job ingestionJob;

    public IngestionService(
            Inbox inbox,
            IngestionBatchRepository batches,
            SchemaDefinition schema,
            JobOperator jobOperator,
            Job ingestionJob) {
        this.inbox = inbox;
        this.batches = batches;
        this.schema = schema;
        this.jobOperator = jobOperator;
        this.ingestionJob = ingestionJob;
    }

    public IngestionResponse ingest(@Nullable String originalFilename, InputStreamSource content) throws IOException {
        // AC-01.2: the extension decides, never the part's Content-Type. JSON waits for FR-02 (owner, 2026-10-22).
        if (originalFilename == null
                || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new UnsupportedFileTypeException(originalFilename);
        }
        String filename = sanitize(originalFilename);
        UUID batchId = UUID.randomUUID();
        Path directory = inbox.directory(batchId);
        try {
            Files.createDirectories(directory);
            Path source = directory.resolve(Inbox.SOURCE_FILE);
            MessageDigest sha256 = sha256();
            long size;
            try (InputStream in = new DigestInputStream(content.getInputStream(), sha256)) {
                size = Files.copy(in, source);
            }
            if (size == 0 || hasNoRecords(batchId)) {
                throw new EmptyFileException();
            }
            String checksum = HexFormat.of().formatHex(sha256.digest());
            // Committed before the launch: the job runs on another thread and must find the row.
            OffsetDateTime receivedAt =
                    batches.insert(batchId, filename, checksum, size, schema.name(), schema.version());
            launch(batchId, checksum);
            String status = "RECEIVED".equals(batches.status(batchId)) ? "RECEIVED" : "RUNNING";
            return new IngestionResponse(batchId, status, filename, "CSV", checksum, receivedAt);
        } catch (IOException | RuntimeException e) {
            FileSystemUtils.deleteRecursively(directory);
            throw e;
        }
    }

    private void launch(UUID batchId, String checksum) {
        try {
            jobOperator.start(
                    ingestionJob,
                    new JobParametersBuilder()
                            .addString(IngestionJobConfiguration.BATCH_ID, batchId.toString())
                            .addString(IngestionJobConfiguration.SOURCE_CHECKSUM, checksum)
                            .toJobParameters());
        } catch (Exception e) {
            batches.fail(batchId, "INFRASTRUCTURE_ERROR");
            throw new IllegalStateException("cannot launch the ingestion job for batch " + batchId, e);
        }
    }

    /**
     * True when the file has a header and no record (AC-01.3). A file that cannot be read at all is not "empty": the
     * batch is created and the job rejects it with INVALID_FILE_FORMAT (AC-01.7, AC-01.8).
     */
    private boolean hasNoRecords(UUID batchId) {
        RawRecordReader reader = new RawRecordReader(inbox.source(batchId));
        try {
            reader.open(new ExecutionContext());
            return reader.read() == null;
        } catch (InvalidFileFormatException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the received file of batch " + batchId, e);
        } finally {
            reader.close();
        }
    }

    /** Only the last path segment, without control characters: it is stored, never used to build a path (AC-01.6). */
    static String sanitize(String filename) {
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        name = name.codePoints()
                .filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return name.length() > MAX_FILENAME_LENGTH ? name.substring(name.length() - MAX_FILENAME_LENGTH) : name;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
