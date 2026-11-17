package com.pipemend.pipeline.persistence;

import com.pipemend.pipeline.batch.ProcessedRecord;
import com.pipemend.pipeline.policy.AppliedCorrection;
import com.pipemend.pipeline.transform.SalesTransaction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * Routes each outcome to its table within the chunk transaction (NFR-05, AC-10.4): loaded rows only to
 * sales_transaction, with their corrections in correction_log, and quarantined rows only to quarantine_record. Always
 * in the order sales_transaction, correction_log, quarantine_record, whatever the order of the chunk, because the
 * composite foreign key of correction_log needs its clean row first (docs/05 section 4). Any database error propagates
 * and fails the chunk.
 */
public final class ProcessedRecordWriter implements ItemWriter<ProcessedRecord> {

    private final SalesTransactionWriter sales;
    private final CorrectionLogWriter corrections;
    private final QuarantineWriter quarantine;

    public ProcessedRecordWriter(
            SalesTransactionWriter sales, CorrectionLogWriter corrections, QuarantineWriter quarantine) {
        this.sales = sales;
        this.corrections = corrections;
        this.quarantine = quarantine;
    }

    @Override
    public void write(Chunk<? extends ProcessedRecord> chunk) throws Exception {
        List<SalesTransaction> loaded = new ArrayList<>();
        List<CorrectionLogWriter.Row> corrected = new ArrayList<>();
        List<ProcessedRecord.Quarantined> quarantined = new ArrayList<>();
        for (ProcessedRecord record : chunk) {
            switch (record) {
                case ProcessedRecord.Loaded l -> {
                    SalesTransaction row = l.transaction();
                    loaded.add(row);
                    for (AppliedCorrection correction : l.corrections()) {
                        corrected.add(new CorrectionLogWriter.Row(row.batchId(), row.sourceRowNumber(), correction));
                    }
                }
                case ProcessedRecord.Quarantined q -> quarantined.add(q);
            }
        }
        sales.write(new Chunk<>(loaded));
        corrections.write(new Chunk<>(corrected));
        quarantine.write(new Chunk<>(quarantined));
    }
}
