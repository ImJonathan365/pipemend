package com.pipemend.pipeline.ingestion;

import java.util.List;

/** The paginated response shared by the lists of docs/06 B. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, long totalPages) {}
