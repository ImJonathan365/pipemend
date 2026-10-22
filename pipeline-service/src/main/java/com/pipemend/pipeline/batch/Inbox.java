package com.pipemend.pipeline.batch;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Where a received file lives (AC-01.6). The path comes only from the batch id, never from the original name. */
@Component
public class Inbox {

    public static final String SOURCE_FILE = "source.csv";

    private final Path root;

    public Inbox(@Value("${pipemend.inbox-dir}") Path root) {
        this.root = root;
    }

    public Path directory(UUID batchId) {
        return root.resolve(batchId.toString());
    }

    public Resource source(UUID batchId) {
        return new FileSystemResource(directory(batchId).resolve(SOURCE_FILE));
    }
}
