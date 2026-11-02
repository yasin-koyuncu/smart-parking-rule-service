package com.parkview.ruleengine.service.retention;

import java.util.function.IntSupplier;

/** The batch loop of the retention job: a batch is one short transaction, the loop ends when the backlog is gone or the budget is. */
final class RetentionBatches {

    private RetentionBatches() {
    }

    /**
     * @param rows    rows processed
     * @param more    {@code true} when the budget ended on a full batch, so there may be more; the next run continues
     */
    record Drained(long rows, boolean more) {
    }

    /**
     * Runs {@code batch} (which returns the number of rows it processed, at most {@code batchSize}) until a batch comes back short or
     * {@code maxBatches} batches ran.
     */
    static Drained drain(int batchSize, int maxBatches, IntSupplier batch) {
        long rows = 0;
        for (int batches = 0; batches < maxBatches; batches++) {
            int processed = batch.getAsInt();
            rows += processed;
            if (processed < batchSize) {
                return new Drained(rows, false);
            }
        }
        return new Drained(rows, true);
    }
}
