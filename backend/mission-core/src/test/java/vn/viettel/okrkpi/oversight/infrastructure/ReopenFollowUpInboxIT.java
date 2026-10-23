package vn.viettel.okrkpi.oversight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.appraisal.api.ProfileFormsApi;
import vn.viettel.okrkpi.config.api.PeriodReopenExpiredEvent;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenFollowUpService;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenWorkflow;
import vn.viettel.okrkpi.oversight.infrastructure.client.AppraisalReopenForms;
import vn.viettel.okrkpi.oversight.infrastructure.client.AppraisalScopeProfiles;
import vn.viettel.okrkpi.oversight.infrastructure.listener.ReopenExpiredListener;
import vn.viettel.okrkpi.oversight.infrastructure.listener.ReopenFollowUpListener;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.AnomalyAlertConfiguration;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.JdbcReopenProgressRepository;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.JdbcReopenRequestRepository;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.OutboxOversightAudit;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.OutboxReopenNotices;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.ReopenFollowUpConfiguration;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;
import vn.vtokr.scoring.v1.DiemDaTinh;

/**
 * CORE-07-09 trên PostgreSQL thật: EVT-10 → EVT-17 → EVT-18 → EVT-09 đi qua bộ xử lý inbox của
 * schema oversight (thân Avro thay bằng bộ giải mã kiểm thử), với SVC-07, SVC-11, SVC-10 giả là các
 * sự kiện dựng tay và MOD-08 giả là cổng khoá lại sớm ghi lại lời gọi. Biểu mẫu tra thật trên
 * {@code dossier.form_instance}. Kịch bản thứ hai hết hạn giữa chừng.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.oversight.reopen-execution.enabled=false",
      "mission.oversight.anomaly-digest.enabled=false"
    })
class ReopenFollowUpInboxIT {

  private static final int BUCKET = 20271;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private JdbcReopenRequestRepository requests;
  @Autowired private JdbcReopenProgressRepository progress;
  @Autowired private ProfileFormsApi formsApi;
  @Autowired private ObjectMapper json;
  @Autowired private InboxProperties inboxProperties;
  @Autowired private InboxRetryDlqPublisher dlq;
  @Autowired private InboxMetrics metrics;

  @Autowired
  @Qualifier("oversightDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("oversightTransactionManager") private PlatformTransactionManager tm;

  @Autowired
  @Qualifier("dossierDataSource") private DataSource dossierDataSource;

  @Autowired
  @Qualifier("dossierTransactionManager") private PlatformTransactionManager dossierTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID requester = UUID.randomUUID();
  private final List<UUID> relocks = new ArrayList<>();
  private org.apache.avro.specific.SpecificRecord current;

  @Test
  void suKienTinhLaiSinhLaiKyLaiRoiKhoaLaiDuaYeuCauToiDaDong() throws Exception {
    partitions();
    UUID profile = UUID.randomUUID();
    UUID mau2 = form(profile, "MAU_2");
    UUID mau3 = form(profile, "MAU_3");
    ReopenRequest r = executed(profile);
    ReopenFollowUpService service = service();
    inTenant(
        jdbc -> {
          service.seedExecuted(tenant);
          return null;
        });
    Map<String, InboxEndpoint> endpoints = endpoints(service);
    InboxMessageProcessor processor = processor();
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    // Ký Mẫu 3 đến trước khi sinh (sai thứ tự), EVT-10 nhận trùng.
    current = signed(mau3, t0.plusSeconds(40));
    assertThat(send(processor, endpoints, EventTopics.EVT_18_KY_SO_DA_HOAN_TAT, null))
        .isEqualTo(InboxOutcome.PROCESSED);
    current = scored(profile);
    UUID scoreEvent = UUID.randomUUID();
    assertThat(send(processor, endpoints, EventTopics.EVT_10_DIEM_DA_TINH, scoreEvent, t0))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(send(processor, endpoints, EventTopics.EVT_10_DIEM_DA_TINH, scoreEvent, t0))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    current = generated(mau2, "MAU_2");
    send(processor, endpoints, EventTopics.EVT_17_HO_SO_SO_DA_SINH, null, t0.plusSeconds(10));
    current = generated(mau3, "MAU_3");
    send(processor, endpoints, EventTopics.EVT_17_HO_SO_SO_DA_SINH, null, t0.plusSeconds(10));
    assertThat(relocks).isEmpty();
    current = signed(mau2, t0.plusSeconds(30));
    send(processor, endpoints, EventTopics.EVT_18_KY_SO_DA_HOAN_TAT, null);
    assertThat(relocks).containsExactly(r.id());

    current = locked(r.id(), t0.plusSeconds(60));
    send(processor, endpoints, EventTopics.EVT_09_KY_DA_KHOA, null);
    ReopenRequest closed = inTenant(jdbc -> requests.find(tenant, r.id())).orElseThrow();
    assertThat(closed.status()).isEqualTo(ReopenStatus.DA_DONG);
    assertThat(closed.relockedAt()).isEqualTo(t0.plusSeconds(60));
    // Khoá followUp không làm mất phạm vi chi tiết; tiến độ còn đủ biểu mẫu.
    assertThat(scopeJson(r.id())).contains("\"relockRequestedAt\"").contains("\"items\"");
    assertThat(closed.basis().code()).isEqualTo("QĐ 12");
    assertThat(progressOf(r.id())).hasSize(1);
    assertThat(progressOf(r.id()).get(0).get("completed_at")).isNotNull();
    assertThat(audits()).contains("THEO_DOI_HAU_KIEM", "HOAN_TAT_HAU_KIEM", "DONG_YEU_CAU_MO_LAI");
    assertThat(notices()).contains("MO_LAI_KY_DA_DONG");

    // EVT-09 khác mã sau khi đóng: không đổi gì.
    current = locked(r.id(), t0.plusSeconds(120));
    send(processor, endpoints, EventTopics.EVT_09_KY_DA_KHOA, null);
    assertThat(inTenant(jdbc -> requests.find(tenant, r.id())).orElseThrow().relockedAt())
        .isEqualTo(t0.plusSeconds(60));
  }

  @Test
  void hetHanGiuaChungDanhDauChuaHoanTatHauKiem() throws Exception {
    partitions();
    UUID profile = UUID.randomUUID();
    form(profile, "MAU_2");
    ReopenRequest r = executed(profile);
    ReopenFollowUpService service = service();
    Map<String, InboxEndpoint> endpoints = endpoints(service);
    InboxMessageProcessor processor = processor();
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    current = scored(profile);
    send(processor, endpoints, EventTopics.EVT_10_DIEM_DA_TINH, null, t0);

    // Bean thật có @Transactional; ở đây dựng trực tiếp nên mở giao dịch để RLS thấy ngữ cảnh.
    inTenant(
        jdbc -> {
          new ReopenExpiredListener(service)
              .on(new PeriodReopenExpiredEvent(tenant, PeriodId.of(period), r.id(), t0));
          return null;
        });
    assertThat(scopeJson(r.id())).contains("\"incompleteAt\"").contains("\"pendingProfiles\": 1");
    assertThat(notices()).contains("HAU_KIEM_CHUA_HOAN_TAT");
    assertThat(relocks).isEmpty();

    current = locked(r.id(), t0.plusSeconds(5));
    send(processor, endpoints, EventTopics.EVT_09_KY_DA_KHOA, null);
    ReopenRequest closed = inTenant(jdbc -> requests.find(tenant, r.id())).orElseThrow();
    assertThat(closed.status()).isEqualTo(ReopenStatus.DA_DONG);
    // Thay đổi đã làm được giữ: tiến độ tính lại vẫn còn.
    assertThat(progressOf(r.id()).get(0).get("recomputed_at")).isNotNull();
  }

  // ---------------------------------------------------------------- hỗ trợ

  private ReopenFollowUpService service() {
    OutboxWriter writer =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "oversight",
            (topic, payload) ->
                new SerializedPayload(payload.toString().getBytes(StandardCharsets.UTF_8), 1L, 1),
            json,
            Clock.systemUtc());
    return new ReopenFollowUpService(
        requests,
        progress,
        new AppraisalScopeProfiles(null),
        new AppraisalReopenForms(formsApi),
        (t, id, actor) -> relocks.add(id),
        null,
        new OutboxReopenNotices(writer),
        new OutboxOversightAudit(writer),
        java.time.Clock.systemUTC(),
        Set.of("MAU_2", "MAU_3"));
  }

  private Map<String, InboxEndpoint> endpoints(ReopenFollowUpService service) {
    Map<String, InboxEndpoint> out = new java.util.HashMap<>();
    for (InboxEndpoint e :
        ReopenFollowUpConfiguration.endpoints(new ReopenFollowUpListener(service))) {
      out.put(e.topic(), e);
    }
    return out;
  }

  private InboxMessageProcessor processor() {
    return AnomalyAlertConfiguration.oversightInboxProcessor(
        dataSource, tm, inboxProperties, (topic, headers, data) -> current, dlq, metrics);
  }

  private InboxOutcome send(
      InboxMessageProcessor p, Map<String, InboxEndpoint> endpoints, String topic, UUID eventId)
      throws Exception {
    return send(p, endpoints, topic, eventId, Instant.now());
  }

  private InboxOutcome send(
      InboxMessageProcessor p,
      Map<String, InboxEndpoint> endpoints,
      String topic,
      UUID eventId,
      Instant occurredAt) {
    ConsumerRecord<String, byte[]> rec =
        new ConsumerRecord<>(topic, 0, 0L, tenant.value() + ":k", new byte[] {0});
    UUID id = eventId == null ? UUID.randomUUID() : eventId;
    rec.headers().add(new RecordHeader(OutboxHeaders.EVENT_ID, bytes(id.toString())));
    rec.headers().add(new RecordHeader(OutboxHeaders.TENANT_ID, bytes(tenant.value().toString())));
    rec.headers().add(new RecordHeader(OutboxHeaders.OCCURRED_AT, bytes(occurredAt.toString())));
    return p.process(endpoints.get(topic), rec, () -> {});
  }

  private DiemDaTinh scored(UUID profile) {
    return DiemDaTinh.newBuilder()
        .setScoreResultId(UUID.randomUUID())
        .setProfileId(profile)
        .setJobKind("TINH_LAI_HAU_KIEM")
        .setTotalScore(new java.math.BigDecimal("88.500"))
        .setAutoRatingCode("HOAN_THANH_TOT")
        .setInputHash(ByteBuffer.wrap(new byte[32]))
        .setPolicyContentHash(ByteBuffer.wrap(new byte[32]))
        .setAggregateVersion(2)
        .build();
  }

  private HoSoSoDaSinh generated(UUID formId, String code) {
    return HoSoSoDaSinh.newBuilder()
        .setRenderJobId(UUID.randomUUID())
        .setFormInstanceId(formId)
        .setFormCode(code)
        .setObjectKey("t/" + formId + ".pdf")
        .setPdfSha256(ByteBuffer.wrap(new byte[32]))
        .setAggregateVersion(1)
        .build();
  }

  private KySoDaHoanTat signed(UUID formId, Instant at) {
    return KySoDaHoanTat.newBuilder()
        .setSignRequestId(UUID.randomUUID())
        .setPurpose("KY_BIEU_MAU")
        .setSourceModule("dossier")
        .setSourceRefId(formId)
        .setItems(
            List.of(
                SignedItem.newBuilder()
                    .setDocumentSha256(ByteBuffer.wrap(new byte[32]))
                    .setSignedObjectKey(null)
                    .build()))
        .setCertSha256(ByteBuffer.wrap(new byte[32]))
        .setSignedAt(at)
        .setAggregateVersion(1)
        .build();
  }

  private KyDaKhoa locked(UUID requestId, Instant at) {
    return KyDaKhoa.newBuilder()
        .setPeriodId(period)
        .setPeriodBucket(BUCKET)
        .setLockedAt(at)
        .setSagaId(requestId)
        .setPolicyPackVersionIds(List.of())
        .setAggregateVersion(5)
        .build();
  }

  private ReopenRequest executed(UUID profile) throws Exception {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    ReopenRequest r =
        new ReopenRequest(
            UUID.randomUUID(),
            period,
            BUCKET,
            "MLK-2027-" + (100 + (int) (Math.random() * 800)),
            ReopenScope.HO_SO,
            List.of(profile),
            List.of(),
            List.of(),
            new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ 12"),
            "DOT_XUAT",
            "Kết luận kiểm tra",
            requester,
            2,
            ReopenStatus.DA_DUYET,
            10,
            now.plus(10, ChronoUnit.DAYS),
            null,
            null,
            null,
            ReopenWorkflow.empty(null),
            now,
            0);
    inTenant(
        jdbc -> {
          requests.insert(tenant, r, requester);
          return null;
        });
    ReopenRequest executed = r.with(ReopenStatus.DA_THUC_HIEN, now, null, r.workflow(), 1);
    inTenant(jdbc -> requests.update(tenant, executed, 0, requester));
    return executed;
  }

  private UUID form(UUID profile, String code) throws Exception {
    UUID id = UUID.randomUUID();
    JdbcClient jdbc = JdbcClient.create(dossierDataSource);
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(dossierTm)
                .execute(
                    s ->
                        jdbc.sql(
                                "INSERT INTO dossier.form_instance (id, created_by, updated_by,"
                                    + " period_id, period_bucket, profile_id, form_code,"
                                    + " template_version, data_snapshot_hash) VALUES (?, ?, ?, ?,"
                                    + " ?, ?, ?, '1.0.0', ?)")
                            .params(
                                id,
                                requester,
                                requester,
                                period,
                                BUCKET,
                                profile,
                                code,
                                new byte[32])
                            .update()));
    return id;
  }

  private void partitions() throws Exception {
    inTenant(
        jdbc -> {
          for (String t : List.of("reopen_request", "reopen_progress")) {
            jdbc.sql("SELECT oversight.fn_create_period_partitions(?::regclass, ?)")
                .params("oversight." + t, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
    JdbcClient d = JdbcClient.create(dossierDataSource);
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(dossierTm)
                .execute(
                    s ->
                        d.sql("SELECT dossier.fn_create_period_partitions(?::regclass, ?)")
                            .params("dossier.form_instance", BUCKET)
                            .query()
                            .listOfRows()));
  }

  private String scopeJson(UUID id) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT scope_json::text FROM oversight.reopen_request WHERE id = ?")
                .param(id)
                .query(String.class)
                .single());
  }

  private List<Map<String, Object>> progressOf(UUID id) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT * FROM oversight.reopen_progress WHERE reopen_request_id = ?")
                .param(id)
                .query()
                .listOfRows());
  }

  private String audits() throws Exception {
    return outbox(EventTopics.SYS_AUDIT_V1);
  }

  private String notices() throws Exception {
    return outbox(EventTopics.EVT_21_YEU_CAU_THONG_BAO);
  }

  /** Thân outbox ở bộ tuần tự kiểm thử là văn bản của bản ghi Avro. */
  private String outbox(String topic) throws Exception {
    List<String> rows =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT convert_from(payload, 'UTF8') FROM oversight.outbox_event"
                            + " WHERE topic = ? AND tenant_id = ?")
                    .param(topic)
                    .param(tenant.value())
                    .query(String.class)
                    .list());
    return String.join("\n", rows);
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private <T> T inTenant(Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(jdbc)));
  }
}
