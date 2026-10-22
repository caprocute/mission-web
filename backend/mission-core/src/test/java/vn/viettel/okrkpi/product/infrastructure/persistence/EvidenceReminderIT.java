package vn.viettel.okrkpi.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.product.application.evidence.EvidenceReminderService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-03-08 trên PostgreSQL thật: hàng chờ đọc phiên bản {@code DA_NOP} có người
 * nghiệm thu, lịch quốc gia của {@code config.holiday_calendar} (30/4, 1/5 và nghỉ bù), sổ giữ chỗ
 * {@code product.idempotency_key}; chạy hai lần trong ngày chỉ ghi một EVT-21 nhắc người nghiệm thu
 * vào {@code product.outbox_event}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.product.acceptance-reminder.enabled=false"
    })
@Import(EvidenceReminderIT.SerializerConfig.class)
@DirtiesContext
class EvidenceReminderIT {

  static final int BUCKET = 20272;

  /** Bộ tuần tự outbox giả (môi trường kiểm thử không có registry Avro). */
  static class SerializerConfig {
    @Bean
    @Primary
    OutboxPayloadSerializer reminderTestSerializer() {
      return (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private EvidenceReminderService reminders;

  @Autowired
  @Qualifier("productDataSource") private DataSource productDataSource;

  @Autowired
  @Qualifier("productTransactionManager") private PlatformTransactionManager productTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID lead = UUID.randomUUID();
  private final UUID reviewer = UUID.randomUUID();

  @Test
  void nhacMotLanMoiNgayTheoNgayLamViec() throws Exception {
    in(
        jdbc -> {
          for (String t : List.of("deliverable", "evidence", "evidence_version")) {
            jdbc.sql("SELECT product.fn_create_period_partitions(?::regclass, ?)")
                .params("product." + t, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
    // Nộp thứ Ba 20/4/2027; quá 5 ngày làm việc từ thứ Tư 28/4 (ngày làm việc thứ 6).
    seedSubmitted(Instant.parse("2027-04-20T03:00:00Z"));
    seedSubmitted(Instant.parse("2027-04-20T04:00:00Z"));

    assertThat(in(jdbc -> reminders.remind(tenant, LocalDate.of(2027, 4, 27))).reviewers())
        .isZero();
    assertThat(in(jdbc -> reminders.remind(tenant, LocalDate.of(2027, 4, 28))).reviewers())
        .isEqualTo(1);
    assertThat(in(jdbc -> reminders.remind(tenant, LocalDate.of(2027, 4, 28))).reviewers())
        .isZero();
    // 1/5/2027 là thứ Bảy và ngày nghỉ: không chạy.
    assertThat(in(jdbc -> reminders.remind(tenant, LocalDate.of(2027, 5, 1))).reviewers()).isZero();
    Integer written =
        in(
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM product.outbox_event WHERE topic = ? AND"
                            + " aggregate_type = 'evidence_reminder'")
                    .param(EventTopics.EVT_21_YEU_CAU_THONG_BAO)
                    .query(Integer.class)
                    .single());
    assertThat(written).isEqualTo(1);
  }

  private void seedSubmitted(Instant submittedAt) throws Exception {
    UUID task = UUID.randomUUID();
    UUID deliverable = UUID.randomUUID();
    UUID evidence = UUID.randomUUID();
    byte[] sha = new byte[32];
    sha[0] = 7;
    in(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO product.deliverable (id, created_by, updated_by, period_id,"
                      + " period_bucket, task_id, name, deliverable_type, unit, quantity_planned)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'Báo cáo quý', 'BAO_CAO', 'báo cáo', 1)")
              .params(deliverable, lead, lead, period, BUCKET, task)
              .update();
          jdbc.sql(
                  "INSERT INTO product.evidence (id, created_by, updated_by, period_id,"
                      + " period_bucket, deliverable_id, task_id, title, source_type, reviewer_id)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, 'Báo cáo tổng kết', 'TAI_LEN', ?)")
              .params(evidence, lead, lead, period, BUCKET, deliverable, task, reviewer)
              .update();
          jdbc.sql(
                  "INSERT INTO product.evidence_version (id, created_by, updated_by, period_id,"
                      + " period_bucket, evidence_id, task_id, version_no, object_id,"
                      + " content_sha256, source, scan_status) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?,"
                      + " ?, 'TAI_LEN', 'SACH')")
              .params(
                  UUID.randomUUID(),
                  lead,
                  lead,
                  period,
                  BUCKET,
                  evidence,
                  task,
                  UUID.randomUUID(),
                  sha)
              .update();
          jdbc.sql(
                  "UPDATE product.evidence_version SET status = 'DA_NOP', submitted_by = ?,"
                      + " submitted_at = ? WHERE evidence_id = ?")
              .params(lead, java.sql.Timestamp.from(submittedAt), evidence)
              .update();
          return null;
        });
  }

  private <T> T in(Function<JdbcClient, T> work) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(productTm)
                .execute(s -> work.apply(JdbcClient.create(productDataSource))));
  }
}
