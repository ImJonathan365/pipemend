package com.pipemend.pipeline.persistence;

import com.pipemend.pipeline.batch.ProcessedRecord;
import com.pipemend.pipeline.transform.SalesTransaction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * Routes each outcome to its table within the chunk transaction (NFR-05): loaded rows only to sales_transaction,
 * quarantined rows only to quarantine_record. Clean rows are written first because correction_log will reference them
 * (docs/05 section 4). Any database error propagates and fails the chunk.
 */
public final class ProcessedRecordWriter implements ItemWriter<ProcessedRecord> {

    private final SalesTransactionWriter sales;
    private final QuarantineWriter quarantine;

    public ProcessedRecordWriter(SalesTransactionWriter sales, QuarantineWriter quarantine) {
        this.sales = sales;
        this.quarantine = quarantine;
    }

    @Override
    public void write(Chunk<? extends ProcessedRecord> chunk) throws Exception {
        List<SalesTransaction> loaded = new ArrayList<>();
        List<ProcessedRecord.Quarantined> quarantined = new ArrayList<>();
        for (ProcessedRecord record : chunk) {
            switch (record) {
                case ProcessedRecord.Loaded l -> loaded.add(l.transaction());
                case ProcessedRecord.Quarantined q -> quarantined.add(q);
            }
        }
        sales.write(new Chunk<>(loaded));
        quarantine.write(new Chunk<>(quarantined));
    }
}
