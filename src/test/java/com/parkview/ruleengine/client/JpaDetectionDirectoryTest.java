package com.parkview.ruleengine.client;

import com.parkview.ruleengine.client.DetectionDirectory.Detection;
import com.parkview.ruleengine.readmodel.DetectionViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The detections of ingestion-service through the read model: which snapshots belong to a plate, a zone and a time window. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaDetectionDirectoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("test-schema.sql");

    private static final Instant FROM = Instant.parse("2026-10-09T09:40:00Z");
    private static final Instant TO = Instant.parse("2026-10-09T10:00:00Z");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DetectionViewRepository views;

    private JpaDetectionDirectory directory;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from detections");
        directory = new JpaDetectionDirectory(views);
    }

    private UUID detection(String plate, String zone, Instant at, String image, Double confidence, String review, String corrected) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into detections (id, plate, corrected_plate, zone_id, at, image_path, confidence, review_status) values (?,?,?,?,?,?,?,?)",
                id, plate, corrected, zone, Timestamp.from(at), image, confidence, review);
        return id;
    }

    @Test
    void returnsTheDetectionsOfThePlateInTheZoneAndWindowNewestFirstWithSnapshotAndConfidence() {
        UUID older = detection("ABC123", "z1", FROM.plusSeconds(60), "snapshots/cam-1/a.jpg", 0.87, "auto", null);
        UUID newer = detection("ABC123", "z1", FROM.plusSeconds(600), "snapshots/cam-1/b.jpg", 0.93, "approved", null);

        List<Detection> found = directory.involvedIn("ABC123", "z1", FROM, TO, 10);

        assertThat(found).extracting(Detection::id).containsExactly(newer, older);
        assertThat(found.get(0).imagePath()).isEqualTo("snapshots/cam-1/b.jpg");
        assertThat(found.get(0).confidence()).isEqualByComparingTo("0.93");
        assertThat(found.get(1).at()).isEqualTo(FROM.plusSeconds(60));
    }

    @Test
    void otherPlatesZonesAndTimesAreLeftOut() {
        detection("XYZ999", "z1", FROM.plusSeconds(60), "x.jpg", 0.9, "auto", null);
        detection("ABC123", "z2", FROM.plusSeconds(60), "x.jpg", 0.9, "auto", null);
        detection("ABC123", "z1", FROM.minusSeconds(1), "x.jpg", 0.9, "auto", null);
        detection("ABC123", "z1", TO.plusSeconds(1), "x.jpg", 0.9, "auto", null);

        assertThat(directory.involvedIn("ABC123", "z1", FROM, TO, 10)).isEmpty();
    }

    @Test
    void aReviewerCorrectionCountsForThePlateItWasCorrectedTo() {
        UUID corrected = detection("ABC12E", "z1", FROM.plusSeconds(60), "snapshots/cam-1/c.jpg", 0.6, "approved", "ABC123");

        assertThat(directory.involvedIn("ABC123", "z1", FROM, TO, 10)).extracting(Detection::id).containsExactly(corrected);
        assertThat(directory.involvedIn("ABC12E", "z1", FROM, TO, 10)).extracting(Detection::id).containsExactly(corrected);
    }

    @Test
    void rejectedDetectionsAreNotEvidenceButPendingOnesAre() {
        detection("ABC123", "z1", FROM.plusSeconds(60), "snapshots/cam-1/r.jpg", 0.4, "rejected", null);
        UUID pending = detection("ABC123", "z1", FROM.plusSeconds(120), "snapshots/cam-1/p.jpg", 0.5, "pending", null);

        assertThat(directory.involvedIn("ABC123", "z1", FROM, TO, 10)).extracting(Detection::id).containsExactly(pending);
    }

    @Test
    void aDetectionWithoutSnapshotOrConfidenceIsStillReturnedAsNulls() {
        UUID typed = detection("ABC123", "z1", FROM.plusSeconds(60), null, null, "auto", null);
        UUID blank = detection("ABC123", "z1", FROM.plusSeconds(30), "  ", null, "auto", null);

        List<Detection> found = directory.involvedIn("ABC123", "z1", FROM, TO, 10);

        assertThat(found).extracting(Detection::id).containsExactly(typed, blank);
        assertThat(found).allSatisfy(d -> {
            assertThat(d.imagePath()).isNull();
            assertThat(d.confidence()).isNull();
        });
    }

    @Test
    void theLimitKeepsTheNewestOnes() {
        for (int i = 0; i < 5; i++) {
            detection("ABC123", "z1", FROM.plusSeconds(60L * (i + 1)), "snapshots/cam-1/" + i + ".jpg", 0.9, "auto", null);
        }

        List<Detection> found = directory.involvedIn("ABC123", "z1", FROM, TO, 2);

        assertThat(found).extracting(Detection::imagePath).containsExactly("snapshots/cam-1/4.jpg", "snapshots/cam-1/3.jpg");
    }
}
