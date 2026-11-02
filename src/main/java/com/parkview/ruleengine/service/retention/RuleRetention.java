package com.parkview.ruleengine.service.retention;

import com.parkview.ruleengine.config.RetentionProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * The data work of the retention job. Each batch is its own short transaction that locks its rows ({@code SKIP LOCKED}), so a big backlog
 * never holds long locks and two runs never touch the same row. All statements are idempotent: a row that was handled no longer matches.
 *
 * <ol>
 *   <li>Evidence: the plate and the references to snapshots and detections ({@code image_paths}, {@code detection_ids}) of the evidence of a
 *       fine issued before the cutoff (and without an open dispute). This is the only change {@code fine_evidence} allows, and only in a
 *       transaction that set {@code parkview.retention} to {@code on}; the rest of the evidence stays as it was frozen. Once the references
 *       are gone, ingestion-service no longer protects those detections and pictures from its own retention.</li>
 *   <li>Closed records: plate and user of decided disputes and of paid or voided fines, their free text (dispute message and decision note,
 *       void reason, the reason of the audit rows) and the picture key of the fine. Amounts, times, states and the ids of operators stay.</li>
 *   <li>Violations that were resolved without ever becoming a fine are deleted. A violation with a fine is kept (the evidence points to it).</li>
 * </ol>
 */
@Component
public class RuleRetention {

    /** What replaces a plate: the column is not null. */
    public static final String ANONYMISED = "ANONYMISED";

    private static final String EVIDENCE_DUE = """
            issued_at < :cutoff
              and (plate <> :marker or image_paths <> '[]'::jsonb or detection_ids <> '[]'::jsonb)
              and not exists (select 1 from fine_disputes d where d.fine_id = fine_evidence.fine_id and d.status = 'open')""";
    private static final String DISPUTES_DUE = """
            status <> 'open' and decided_at < :cutoff
              and (plate <> :marker or opened_by_user_id is not null or decided_by_user_id is not null
                   or message <> '' or decision_note is not null)""";
    private static final String FINES_DUE = """
            coalesce(paid_at, voided_at) < :cutoff
              and (plate <> :marker or user_id is not null or image_path is not null or void_reason is not null)""";
    private static final String ACTIONS_DUE = """
            reason is not null
              and ((action in ('dispute_upheld', 'dispute_voided', 'dispute_withdrawn') and at < :cutoff)
                   or exists (select 1 from fines f where f.id = fine_actions.fine_id and coalesce(f.paid_at, f.voided_at) < :cutoff))""";
    private static final String VIOLATIONS_DUE = "resolved_at is not null and fine_issued_at is null and resolved_at < :cutoff";

    /** What one run did, or in a dry run would do. */
    public record Counts(long evidence, long disputes, long fines, long actions, long violations, boolean backlogRemains) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final RetentionProperties properties;
    private final TransactionTemplate transaction;

    public RuleRetention(NamedParameterJdbcTemplate jdbc, RetentionProperties properties, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Dry run: how many rows a real run would handle now (one count query per step, nothing is changed). */
    public Counts count(Instant evidenceCutoff, Instant recordsCutoff, Instant violationsCutoff) {
        return new Counts(count("fine_evidence", EVIDENCE_DUE, evidenceCutoff), count("fine_disputes", DISPUTES_DUE, recordsCutoff),
                count("fines", FINES_DUE, recordsCutoff), count("fine_actions", ACTIONS_DUE, recordsCutoff),
                count("spot_violations", VIOLATIONS_DUE, violationsCutoff), false);
    }

    public Counts run(Instant evidenceCutoff, Instant recordsCutoff, Instant violationsCutoff) {
        int size = properties.batchSize();
        int budget = properties.maxBatches();
        RetentionBatches.Drained evidence = RetentionBatches.drain(size, budget,
                () -> inTransaction(() -> anonymiseEvidence(evidenceCutoff, size)));
        RetentionBatches.Drained disputes = RetentionBatches.drain(size, budget, () -> inTransaction(() -> update("""
                update fine_disputes set plate = :marker, opened_by_user_id = null, decided_by_user_id = null, message = '', decision_note = null
                where id in (select id from fine_disputes where %s order by decided_at limit :size for update skip locked)
                """.formatted(DISPUTES_DUE), recordsCutoff, size)));
        RetentionBatches.Drained fines = RetentionBatches.drain(size, budget, () -> inTransaction(() -> update("""
                update fines set plate = :marker, user_id = null, image_path = null, void_reason = null
                where id in (select id from fines where %s order by coalesce(paid_at, voided_at) limit :size for update skip locked)
                """.formatted(FINES_DUE), recordsCutoff, size)));
        RetentionBatches.Drained actions = RetentionBatches.drain(size, budget, () -> inTransaction(() -> update("""
                update fine_actions set reason = null
                where id in (select id from fine_actions where %s order by at limit :size for update skip locked)
                """.formatted(ACTIONS_DUE), recordsCutoff, size)));
        RetentionBatches.Drained violations = RetentionBatches.drain(size, budget, () -> inTransaction(() -> update("""
                delete from spot_violations
                where id in (select id from spot_violations where %s order by resolved_at limit :size for update skip locked)
                """.formatted(VIOLATIONS_DUE), violationsCutoff, size)));
        return new Counts(evidence.rows(), disputes.rows(), fines.rows(), actions.rows(), violations.rows(),
                evidence.more() || disputes.more() || fines.more() || actions.more() || violations.more());
    }

    /** Must run in a transaction: the setting that opens the guard of fine_evidence is local to it. */
    private int anonymiseEvidence(Instant cutoff, int size) {
        jdbc.queryForObject("select set_config('parkview.retention', 'on', true)", new MapSqlParameterSource(), String.class);
        return update("""
                update fine_evidence set plate = :marker, image_paths = '[]'::jsonb, detection_ids = '[]'::jsonb
                where fine_id in (select fine_id from fine_evidence where %s order by issued_at limit :size for update skip locked)
                """.formatted(EVIDENCE_DUE), cutoff, size);
    }

    private int update(String sql, Instant cutoff, int size) {
        return jdbc.update(sql, new MapSqlParameterSource().addValue("cutoff", Timestamp.from(cutoff))
                .addValue("marker", ANONYMISED).addValue("size", size));
    }

    private long count(String table, String due, Instant cutoff) {
        Long rows = jdbc.queryForObject("select count(*) from " + table + " where " + due, new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.from(cutoff)).addValue("marker", ANONYMISED), Long.class);
        return rows == null ? 0 : rows;
    }

    private int inTransaction(IntSupplier work) {
        Integer processed = transaction.execute(status -> work.getAsInt());
        return processed == null ? 0 : processed;
    }
}
