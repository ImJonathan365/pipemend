package com.pipemend.pipeline.transform;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A row of sales_transaction (docs/05 section 3.2), typed as it is loaded. */
public record SalesTransaction(
        UUID batchId,
        int sourceRowNumber,
        String invoiceNo,
        boolean cancellation,
        String stockCode,
        @Nullable String description,
        int quantity,
        LocalDateTime invoiceDate,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        @Nullable String customerId,
        String country,
        LoadOrigin loadOrigin) {

    /** load_origin values of docs/05 section 3.2. */
    public enum LoadOrigin {
        DIRECT,
        AUTO_CORRECTED
    }
}
