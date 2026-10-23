package vn.viettel.okrkpi.dossier.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.PathItem;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService;
import vn.viettel.okrkpi.dossier.infrastructure.listener.MeetingMinutesConsumer;
import vn.viettel.okrkpi.dossier.infrastructure.listener.SpecialCaseAssignmentConsumer;
import vn.viettel.okrkpi.dossier.infrastructure.persistence.FieldEncryptorCommentSealer;
import vn.viettel.okrkpi.dossier.infrastructure.persistence.OutboxMeetingNotices;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Kiểm thử tích hợp CORE-06-02 trên PostgreSQL thật: năm thao tác hội nghị kiểm điểm khớp {@code
 * dossiers.yaml}, phạm vi đơn vị và thành phần, gửi dự thảo đếm ngày làm việc theo ví dụ TL-02b và
 * ghi EVT-21 vào outbox, sửa kế hoạch có {@code If-Match}, chặn khi đã họp và khi kỳ khoá.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@Import(DossierMeetingApiIT.NoticeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DossierMeetingApiIT extends ContractConformanceTest {

  static final UUID OFFICER = UUID.fromString("01927a06-0000-7000-8000-0000000000a7");
  static final UUID CHAIR = UUID.fromString("01927a06-0000-7000-8000-0000000000c1");
  static final UUID SECRETARY = UUID.fromString("01927a06-0000-7000-8000-0000000000c2");
  static final UUID MEMBER = UUID.fromString("01927a06-0000-7000-8000-0000000000c4");
  static final UUID OUTSIDER = UUID.fromString("01927a06-0000-7000-8000-0000000000c9");
  static final UUID UNIT = UUID.fromString("01927a06-0000-7000-8000-0000000000f1");
  static final UUID OTHER_UNIT = UUID.fromString("01927a06-0000-7000-8000-0000000000f9");

  static final ThreadLocal<UUID> USER = ThreadLocal.withInitial(() -> OFFICER);
  static final ThreadLocal<UUID> USER_UNIT = ThreadLocal.withInitial(() -> UNIT);

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @MockitoBean private DossierCaller caller;

  /** Cây tổ chức giả: không có phân công nên không chặn theo đơn vị. */
  @MockitoBean private OrgDirectoryApi orgDirectory;

  /**
   * Môi trường kiểm thử không có Apicurio Registry: thay bộ tuần tự Avro của EVT-21 bằng bản giả.
   */
  /** Lớp thường nhập bằng {@code @Import}: {@code @TestConfiguration} lồng bị quét sang IT khác. */
  static class NoticeConfig {

    @Bean
    @Primary
    MeetingNotices testMeetingNotices(
        @Qualifier("dossierDataSource") DataSource dataSource, ObjectMapper json) {
      return new OutboxMeetingNotices(
          new OutboxWriter(
              new JdbcTemplate(dataSource),
              "dossier",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()),
          json);
    }

    /** Mã hoá C2 thật (AES-GCM) với DEK giả, không cần OpenBao. */
    @Bean
    @Primary
    CommentSealer testCommentSealer() {
      DekService deks = org.mockito.Mockito.mock(DekService.class);
      UUID dekId = UUID.randomUUID();
      org.mockito.Mockito.when(deks.getOrCreateActiveDek(any(), any()))
          .thenAnswer(inv -> dek(dekId, inv.getArgument(0)));
      org.mockito.Mockito.when(deks.getDekForDecryption(any(), any()))
          .thenAnswer(inv -> dek(dekId, inv.getArgument(0)));
      return new FieldEncryptorCommentSealer(
          new FieldEncryptor(deks, new JcaAesGcmCryptoProvider()));
    }

    private static CachedDek dek(UUID dekId, UUID tenant) {
      return new CachedDek(
          dekId,
          tenant,
          DekPurpose.GOP_Y_KIEM_DIEM,
          LocalDate.of(2026, 10, 1),
          DekStatus.ACTIVE,
          new byte[32]);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private CommentSealer sealer;
  @Autowired private MeetingProceedingsService proceedings;
  @Autowired private SpecialCaseService specialCases;
  private MeetingMinutesConsumer minutesConsumer;
  @Autowired private MinutesJobs minutesJobs;
  private long offset;
  @Autowired private ObjectMapper json;

  @Autowired
  @Qualifier("dossierDataSource") private DataSource dossierDataSource;

  @Autowired
  @Qualifier("dossierTransactionManager") private PlatformTransactionManager dossierTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID year = UUID.randomUUID();
  private final UUID locked = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.DOSSIERS;
  }

  @BeforeEach
  void caller() {
    USER.remove();
    USER_UNIT.remove();
    when(caller.currentUserId()).thenAnswer(inv -> Optional.of(USER.get()));
    when(caller.currentUnitId()).thenAnswer(inv -> Optional.ofNullable(USER_UNIT.get()));
  }

  /**
   * Consumer EVT-17, EVT-18 với bộ giải mã Avro thô (môi trường kiểm thử không có Apicurio) và
   * topic thử lại giả, như {@code PlanApprovalIT}.
   */
  @BeforeAll
  @SuppressWarnings({"unchecked", "rawtypes"})
  void consumer() {
    org.springframework.kafka.core.KafkaOperations<String, byte[]> kafka =
        org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaOperations.class);
    when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new JdbcTemplate(dossierDataSource),
            dossierTm,
            "dossier",
            (topic, headers, data) ->
                decode(
                    EventTopics.EVT_17_HO_SO_SO_DA_SINH.equals(topic)
                        ? HoSoSoDaSinh.class
                        : KySoDaHoanTat.class,
                    data),
            new InboxRetryDlqPublisher(kafka),
            new InboxMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            new InboxProperties.Retry(null, null, null, null),
            Clock.systemUtc());
    org.springframework.beans.factory.ObjectProvider none =
        new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(
                org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class);
    minutesConsumer = new MeetingMinutesConsumer(proceedings, processor, none, false);
  }

  @SuppressWarnings("unchecked")
  private static SpecificRecord decode(Class<? extends SpecificRecord> type, byte[] data) {
    try {
      return new org.apache.avro.specific.SpecificDatumReader<SpecificRecord>(
              (Class<SpecificRecord>) type)
          .read(
              null,
              org.apache.avro.io.DecoderFactory.get()
                  .binaryDecoder(new java.io.ByteArrayInputStream(data), null));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @BeforeAll
  void seed() throws Exception {
    insertPeriod(year, "Năm 2026", "2026-01-01", "2026-12-31", 20260, "MO");
    insertPeriod(locked, "Năm 2025", "2025-01-01", "2025-12-31", 20250, "DA_KHOA");
    inTenant(
        jdbc -> {
          for (int bucket : List.of(20250, 20260)) {
            jdbc.sql("SELECT dossier.fn_create_period_partitions(?::regclass, ?)")
                .params("dossier.review_meeting", bucket)
                .query()
                .listOfRows();
            jdbc.sql("SELECT dossier.fn_create_period_partitions(?::regclass, ?)")
                .params("dossier.review_comment", bucket)
                .query()
                .listOfRows();
            jdbc.sql("SELECT dossier.fn_create_period_partitions(?::regclass, ?)")
                .params("dossier.remediation_item", bucket)
                .query()
                .listOfRows();
            jdbc.sql("SELECT dossier.fn_create_period_partitions(?::regclass, ?)")
                .params("dossier.special_case", bucket)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @Test
  void taoXemDanhSachKhopHopDongVaPhatLai() throws Exception {
    UUID subject = UUID.randomUUID();
    ObjectNode req = createBody(year, subject);
    MvcResult created = create(req, "tao-" + subject);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/dossiers/meetings", PathItem.HttpMethod.POST);
    JsonNode body = body(created);
    UUID id = UUID.fromString(body.get("id").asText());
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(created.getResponse().getHeader("Location")).endsWith("/dossiers/meetings/" + id);
    assertThat(body.get("status").asText()).isEqualTo("DU_KIEN");
    assertThat(body.get("title").asText()).isEqualTo("Hội nghị kiểm điểm cuối năm 2026");
    assertThat(body.at("/deadlineCheck/workingDays").isNull()).isTrue();
    assertThat(body.at("/participants/2/role").asText()).isEqualTo("NGUOI_DUOC_KIEM_DIEM");
    assertThat(body.at("/participants/3/status").asText()).isEqualTo("CHUA_MO");
    assertThat(body.path("myRole").isNull() || body.path("myRole").isMissingNode()).isTrue();

    MvcResult replay = create(req, "tao-" + subject);
    assertThat(body(replay).get("id").asText()).isEqualTo(id.toString());
    MvcResult again = create(createBody(year, subject), "tao-lai-" + subject);
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("DOSSIER-409-MEETING-EXISTS");

    USER.set(MEMBER);
    USER_UNIT.set(OTHER_UNIT);
    MvcResult got = perform(get("/api/v1/dossiers/meetings/" + id));
    assertThat(got.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(got, "/dossiers/meetings/{id}", PathItem.HttpMethod.GET);
    assertThat(body(got).get("myRole").asText()).isEqualTo("THANH_VIEN");

    MvcResult page = perform(get("/api/v1/dossiers/meetings?period=" + year + "&limit=1"));
    assertThat(page.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(page, "/dossiers/meetings", PathItem.HttpMethod.GET);
    assertThat(body(page).at("/items/0/myRole").asText()).isEqualTo("THANH_VIEN");

    USER.set(OUTSIDER);
    MvcResult none = perform(get("/api/v1/dossiers/meetings?status=DU_KIEN"));
    assertThat(body(none).get("items")).isEmpty();
    assertThat(perform(get("/api/v1/dossiers/meetings?status=SAI")).getResponse().getStatus())
        .isEqualTo(422);
  }

  @Test
  void nguoiNgoaiDonViBi403() throws Exception {
    USER_UNIT.set(OTHER_UNIT);
    MvcResult denied = create(createBody(year, UUID.randomUUID()), UUID.randomUUID().toString());
    assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(denied).get("code").asText()).isEqualTo("COMMON-403-OUT-OF-SCOPE");

    USER_UNIT.set(UNIT);
    UUID id = createdId(UUID.randomUUID());
    USER.set(OUTSIDER);
    USER_UNIT.set(OTHER_UNIT);
    assertThat(perform(get("/api/v1/dossiers/meetings/" + id)).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(
            perform(get("/api/v1/dossiers/meetings/" + UUID.randomUUID()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void guiDuThaoDemNgayTheoViDuVaGhiEvt21() throws Exception {
    UUID subject = UUID.randomUUID();
    UUID id = createdId(subject);

    USER.set(MEMBER);
    MvcResult notSubject = send(id, "\"0\"");
    assertThat(notSubject.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(notSubject).get("code").asText()).isEqualTo("DOSSIER-403-NOT-SUBJECT");

    USER.set(subject);
    assertThat(
            perform(
                    post("/api/v1/dossiers/meetings/" + id + "/send-draft")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(428);
    MvcResult sent = send(id, "\"0\"");
    assertThat(sent.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(sent, "/dossiers/meetings/{id}/send-draft", PathItem.HttpMethod.POST);
    assertThat(body(sent).get("status").asText()).isEqualTo("DA_GUI_DU_THAO");
    assertThat(body(sent).at("/deadlineCheck/passed").asBoolean()).isTrue();
    assertThat(sent.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    String topics =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT string_agg(topic, ',') FROM dossier.outbox_event"
                            + " WHERE aggregate_id = ?")
                    .param(id)
                    .query(String.class)
                    .single());
    assertThat(topics).isEqualTo("notify.message.requested.v1");

    MvcResult twice = send(id, "\"1\"");
    assertThat(twice.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(twice).get("code").asText()).isEqualTo("DOSSIER-409-INVALID-STATE");

    // Ví dụ TL-02b: gửi 16:20 thứ Sáu 08/01/2027, họp thứ Năm 14/01/2027 là 3 ngày làm việc.
    setDraftSentAt(id, "2027-01-08T16:20:00+07:00");
    JsonNode example = body(perform(get("/api/v1/dossiers/meetings/" + id)));
    assertThat(example.at("/deadlineCheck/workingDays").asInt()).isEqualTo(3);
    assertThat(example.at("/deadlineCheck/passed").asBoolean()).isTrue();
    assertThat(example.at("/deadlineCheck/legalRef").asText()).isEqualTo("QĐ Điều 6");
    assertThat(example.get("draftSentAt").asText()).isEqualTo("2027-01-08T16:20:00+07:00");
  }

  @Test
  void suaKeHoachIfMatchLyDoGiuNgayVaDaHop() throws Exception {
    UUID subject = UUID.randomUUID();
    UUID id = createdId(subject);
    MvcResult moved = update(id, "\"0\"", "{\"location\":\"Hội trường lớn\"}");
    assertThat(moved.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(moved, "/dossiers/meetings/{id}", PathItem.HttpMethod.PATCH);
    assertThat(body(moved).get("location").asText()).isEqualTo("Hội trường lớn");
    MvcResult stale = update(id, "\"0\"", "{\"location\":\"Phòng khác\"}");
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(stale).get("code").asText()).isEqualTo("DOSSIER-409-VERSION-CONFLICT");

    USER.set(subject);
    assertThat(send(id, "\"1\"").getResponse().getStatus()).isEqualTo(200);
    // Gửi muộn: thứ Hai 11/01, chỉ còn 12 và 13/01.
    setDraftSentAt(id, "2027-01-11T16:20:00+07:00");
    String reason = "{\"deadlineOverrideReason\":\"Cấp uỷ thống nhất giữ ngày họp.\"}";
    USER.set(OFFICER);
    MvcResult notChair = update(id, "\"2\"", reason);
    assertThat(body(notChair).get("code").asText()).isEqualTo("DOSSIER-403-NOT-CHAIR");
    USER.set(CHAIR);
    USER_UNIT.set(null);
    MvcResult kept = update(id, "\"2\"", reason);
    assertThat(kept.getResponse().getStatus()).isEqualTo(200);
    JsonNode check = body(kept).get("deadlineCheck");
    assertThat(check.get("workingDays").asInt()).isEqualTo(2);
    assertThat(check.get("passed").asBoolean()).isFalse();
    assertThat(check.get("msg").asText()).isEqualTo("MSG-11-01");
    assertThat(check.get("overrideReason").asText()).isEqualTo("Cấp uỷ thống nhất giữ ngày họp.");

    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE dossier.review_meeting SET status = 'DA_HOP', meeting_at = now()"
                        + " WHERE id = ?")
                .param(id)
                .update());
    MvcResult held = update(id, "\"3\"", "{\"location\":\"Phòng họp số 2\"}");
    assertThat(held.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(held).get("code").asText()).isEqualTo("DOSSIER-409-MEETING-HELD");
    assertThat(update(id, "\"3\"", "{}").getResponse().getStatus()).isEqualTo(422);
    assertThat(update(UUID.randomUUID(), "\"0\"", "{\"location\":\"x\"}").getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void kyDaKhoaKhongLapHoiNghi() throws Exception {
    MvcResult r = create(createBody(locked, UUID.randomUUID()), UUID.randomUUID().toString());
    assertThat(r.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(r).get("code").asText()).isEqualTo("DOSSIER-409-PERIOD-LOCKED");
    ObjectNode invalid =
        createBody(year, UUID.randomUUID()).put("secretaryPersonId", CHAIR.toString());
    MvcResult bad = create(invalid, UUID.randomUUID().toString());
    assertThat(bad.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(bad).get("code").asText()).isEqualTo("DOSSIER-422-VALIDATION");
  }

  @Test
  void gopYMaHoaC2ThuKyGhiTaiHoiNghiVaAnDanh() throws Exception {
    UUID subject = UUID.randomUUID();
    UUID id = createdId(subject);
    USER.set(MEMBER);
    String text = "Việc tham mưu rà soát quy hoạch cán bộ còn chậm khoảng hai tuần.";
    MvcResult early = comment(id, subject, "HAN_CHE", "TRUOC_HOI_NGHI", text);
    assertThat(early.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(early).get("code").asText()).isEqualTo("DOSSIER-409-INVALID-STATE");

    USER.set(subject);
    assertThat(send(id, "\"0\"").getResponse().getStatus()).isEqualTo(200);
    USER.set(MEMBER);
    MvcResult created = comment(id, subject, "HAN_CHE", "TRUOC_HOI_NGHI", text);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/dossiers/meetings/{id}/comments", PathItem.HttpMethod.POST);
    assertThat(body(created).get("content").asText()).isEqualTo(text);
    byte[] stored =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT comment_enc FROM dossier.review_comment WHERE meeting_id = ?")
                    .param(id)
                    .query(byte[].class)
                    .single());
    assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("tham mưu");
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("quy ho");

    MvcResult notSecretary = comment(id, subject, "UU_DIEM", "HOI_NGHI", "Ý kiến tại hội nghị");
    assertThat(notSecretary.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(notSecretary).get("code").asText()).isEqualTo("DOSSIER-403-NOT-SECRETARY");
    USER.set(SECRETARY);
    assertThat(
            comment(id, subject, "UU_DIEM", "HOI_NGHI", "Ý kiến tại hội nghị")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    USER.set(OUTSIDER);
    MvcResult outsider = comment(id, subject, "UU_DIEM", "TRUOC_HOI_NGHI", "x");
    assertThat(body(outsider).get("code").asText()).isEqualTo("DOSSIER-403-NOT-PARTICIPANT");

    enableAnonymousComments();
    USER.set(subject);
    MvcResult mine = perform(get("/api/v1/dossiers/meetings/" + id + "/comments?limit=1"));
    assertThat(mine.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(mine, "/dossiers/meetings/{id}/comments", PathItem.HttpMethod.GET);
    JsonNode page = body(mine);
    assertThat(page.get("anonymous").asBoolean()).isTrue();
    assertThat(page.at("/items/0/content").asText()).isEqualTo(text);
    assertThat(page.at("/items/0/commenter").isNull()).isTrue();
    JsonNode next =
        body(
            perform(
                get(
                    "/api/v1/dossiers/meetings/"
                        + id
                        + "/comments?limit=1&cursor="
                        + page.get("nextCursor").asText())));
    assertThat(next.at("/items/0/phase").asText()).isEqualTo("HOI_NGHI");
    USER.set(CHAIR);
    JsonNode chair = body(perform(get("/api/v1/dossiers/meetings/" + id + "/comments")));
    assertThat(chair.at("/items/0/commenter/id").asText()).isEqualTo(MEMBER.toString());
    USER.set(OUTSIDER);
    USER_UNIT.set(OTHER_UNIT);
    assertThat(
            perform(get("/api/v1/dossiers/meetings/" + id + "/comments")).getResponse().getStatus())
        .isEqualTo(403);
  }

  @Test
  void bienBanKetThucKyVaHanCheKyTruoc() throws Exception {
    UUID subject = UUID.randomUUID();
    UUID previousItem = previousRemediationItem(subject, "Nâng cao chất lượng thẩm định hồ sơ.");
    UUID m2 = UUID.randomUUID();
    UUID m3 = UUID.randomUUID();
    ObjectNode req = createBody(year, subject);
    req.withArray("participantPersonIds").add(m2.toString()).add(m3.toString());
    MvcResult created = create(req, UUID.randomUUID().toString());
    UUID id = UUID.fromString(body(created).get("id").asText());
    USER.set(subject);
    assertThat(send(id, "\"0\"").getResponse().getStatus()).isEqualTo(200);
    USER.set(MEMBER);
    assertThat(
            comment(id, subject, "HAN_CHE", "TRUOC_HOI_NGHI", "Chậm tham mưu quy hoạch.")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    USER.set(SECRETARY);
    assertThat(
            comment(id, subject, "HAN_CHE", "HOI_NGHI", "Chưa sát cơ sở.")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    assertThat(comment(id, subject, "UU_DIEM", "HOI_NGHI", "Tận tuỵ.").getResponse().getStatus())
        .isEqualTo(201);

    List<UUID> everyone = List.of(CHAIR, SECRETARY, subject, MEMBER, m2, m3);
    MvcResult tooMany = minutes(id, "\"1\"", everyone, 7);
    assertThat(tooMany.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(tooMany).get("code").asText()).isEqualTo("DOSSIER-422-VOTE-EXCEEDS-ATTENDEES");
    USER.set(MEMBER);
    MvcResult notSecretary = minutes(id, "\"1\"", everyone, 6);
    assertThat(notSecretary.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(notSecretary).get("code").asText()).isEqualTo("DOSSIER-403-NOT-SECRETARY");
    USER.set(SECRETARY);
    MvcResult accepted = minutes(id, "\"1\"", everyone, 6);
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(accepted, "/dossiers/meetings/{id}/minutes", PathItem.HttpMethod.POST);
    String jobId = body(accepted).get("id").asText();
    assertThat(accepted.getResponse().getHeader("Location")).isEqualTo("/api/v1/jobs/" + jobId);
    assertThat(TenantContext.call(tenant, () -> minutesJobs.find(tenant, jobId)))
        .hasValueSatisfying(j -> assertThat(j.job().status().name()).isEqualTo("DANG_CHAY"));
    MvcResult locked = comment(id, subject, "HAN_CHE", "HOI_NGHI", "Muộn.");
    assertThat(body(locked).get("code").asText()).isEqualTo("DOSSIER-409-COMMENTS-LOCKED");

    MvcResult notChair = close(id, "\"2\"");
    assertThat(notChair.getResponse().getStatus()).isEqualTo(403);
    assertThat(body(notChair).get("code").asText()).isEqualTo("DOSSIER-403-NOT-CHAIR");
    USER.set(CHAIR);
    MvcResult closed = close(id, "\"2\"");
    assertThat(closed.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(closed, "/dossiers/meetings/{id}/close", PathItem.HttpMethod.POST);
    JsonNode meeting = body(closed);
    assertThat(meeting.get("status").asText()).isEqualTo("DA_HOP");
    assertThat(meeting.get("commentsLocked").asBoolean()).isTrue();
    assertThat(meeting.at("/vote/HTTNV").asInt()).isEqualTo(5);
    assertThat(close(id, "\"3\"").getResponse().getStatus()).isEqualTo(409);

    MvcResult own =
        perform(get("/api/v1/dossiers/meetings/" + id + "/remediation-items?origin=HOI_NGHI_NAY"));
    assertThat(own.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        own, "/dossiers/meetings/{id}/remediation-items", PathItem.HttpMethod.GET);
    assertThat(body(own).get("items"))
        .extracting(i -> i.get("content").asText())
        .containsExactlyInAnyOrder("Chậm tham mưu quy hoạch.", "Chưa sát cơ sở.");
    byte[] stored =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT content_enc FROM dossier.remediation_item WHERE meeting_id = ?")
                    .param(id)
                    .query(byte[].class)
                    .list()
                    .get(0));
    assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("tham mưu");
    JsonNode previous =
        body(
            perform(get("/api/v1/dossiers/meetings/" + id + "/remediation-items?origin=KY_TRUOC")));
    assertThat(previous.get("items")).hasSize(1);
    assertThat(previous.at("/items/0/id").asText()).isEqualTo(previousItem.toString());
    assertThat(previous.at("/items/0/content").asText())
        .isEqualTo("Nâng cao chất lượng thẩm định hồ sơ.");

    USER.set(MEMBER);
    MvcResult forbidden = remediation(previousItem, "\"0\"", "DANG_KHAC_PHUC");
    assertThat(body(forbidden).get("code").asText()).isEqualTo("DOSSIER-403-NOT-SECRETARY");
    USER.set(SECRETARY);
    MvcResult progressed = remediation(previousItem, "\"0\"", "DA_KHAC_PHUC");
    assertThat(progressed.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(
        progressed, "/dossiers/remediation-items/{id}", PathItem.HttpMethod.PATCH);
    assertThat(body(progressed).at("/verifiedBy/id").asText()).isEqualTo(SECRETARY.toString());
    assertThat(progressed.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

    byte[] sha = new byte[32];
    sha[0] = 42;
    HoSoSoDaSinh generated =
        HoSoSoDaSinh.newBuilder()
            .setRenderJobId(UUID.fromString(jobId))
            .setFormInstanceId(id)
            .setFormCode("BIEN_BAN")
            .setObjectKey("dossier/bien-ban/" + id + ".pdf")
            .setPdfSha256(ByteBuffer.wrap(sha))
            .setAggregateVersion(1)
            .build();
    assertThat(
            deliver(
                minutesConsumer.generatedEndpoint(),
                EventTopics.EVT_17_HO_SO_SO_DA_SINH,
                "HoSoSoDaSinh",
                UUID.randomUUID(),
                generated,
                SECRETARY))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(status(id)).isEqualTo("DA_LAP_BIEN_BAN");
    assertThat(TenantContext.call(tenant, () -> minutesJobs.find(tenant, jobId)))
        .hasValueSatisfying(j -> assertThat(j.job().status().name()).isEqualTo("HOAN_TAT"));
    long invites =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM dossier.outbox_event WHERE aggregate_id = ?"
                            + " AND topic = ?")
                    .params(id, EventTopics.EVT_21_YEU_CAU_THONG_BAO)
                    .query(Long.class)
                    .single());
    assertThat(invites).as("mời góp ý và mời ký biên bản").isEqualTo(2);
    MvcResult source =
        perform(get("/internal/v1/esign-sources/dossier/" + id + "?purpose=BIEN_BAN"));
    JsonNode src = body(source);
    assertThat(src.get("ready").asBoolean()).isTrue();
    assertThat(src.at("/documents/0/sha256").asText()).startsWith("2a00");
    assertThat(src.get("signers")).hasSize(2);

    UUID chairEvent = UUID.randomUUID();
    assertThat(
            deliver(
                minutesConsumer.signedEndpoint(),
                EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
                "KySoDaHoanTat",
                chairEvent,
                signed(id, sha),
                CHAIR))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(
            deliver(
                minutesConsumer.signedEndpoint(),
                EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
                "KySoDaHoanTat",
                chairEvent,
                signed(id, sha),
                CHAIR))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    deliver(
        minutesConsumer.signedEndpoint(),
        EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
        "KySoDaHoanTat",
        UUID.randomUUID(),
        signed(id, sha),
        CHAIR);
    assertThat(status(id)).as("EVT-18 trùng của chủ trì").isEqualTo("DA_LAP_BIEN_BAN");
    deliver(
        minutesConsumer.signedEndpoint(),
        EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
        "KySoDaHoanTat",
        UUID.randomUUID(),
        signed(id, sha),
        SECRETARY);
    assertThat(status(id)).isEqualTo("DA_KY");
  }

  /**
   * CORE-06-05: tạo hội nghị phát hiện điều động dưới hai tháng của người được kiểm điểm từ bản sao
   * tổ chức; danh sách gom nhóm không lộ chi tiết, đọc một đối tượng giải mã chi tiết, xác minh có
   * {@code If-Match}; EVT-15 trùng không tạo dòng trùng.
   */
  @Test
  void truongHopDacThuKhiTaoHoiNghiVaQuaEvt15() throws Exception {
    UUID subject = UUID.randomUUID();
    when(orgDirectory.assignmentsOf(eq(tenant), eq(subject), any()))
        .thenReturn(
            List.of(
                assignment(subject, OTHER_UNIT, "CHINH", "2020-01-01", "2026-11-14"),
                assignment(subject, UNIT, "DIEU_DONG", "2026-11-15", null)));
    assertThat(
            create(createBody(year, subject), UUID.randomUUID().toString())
                .getResponse()
                .getStatus())
        .isEqualTo(201);

    MvcResult page =
        perform(
            get("/api/v1/dossiers/special-cases?period=" + year + "&unit=" + UNIT + "&limit=1"));
    assertThat(page.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(page, "/dossiers/special-cases", PathItem.HttpMethod.GET);
    JsonNode list = body(page);
    assertThat(list.at("/items/0/caseCode").asText()).isEqualTo("DIEU_DONG_DUOI_2_THANG");
    assertThat(list.at("/items/0/effect").asText()).isEqualTo("XEP_LOAI_TAI_DON_VI_CU");
    assertThat(list.at("/items/0/effectiveFrom").asText()).isEqualTo("2026-11-15");
    assertThat(list.at("/items/0/detail").isMissingNode() || list.at("/items/0/detail").isNull())
        .isTrue();
    assertThat(list.at("/groups/0/count").asInt()).isEqualTo(1);
    assertThat(list.at("/groups/0/handling").asText()).startsWith("Đơn vị cũ đánh giá");
    UUID id = UUID.fromString(list.at("/items/0/id").asText());

    MvcResult one = perform(get("/api/v1/dossiers/special-cases/" + id));
    assertThat(one.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(one, "/dossiers/special-cases/{id}", PathItem.HttpMethod.GET);
    assertThat(one.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(body(one).get("detail").asText()).contains(OTHER_UNIT.toString());

    ObjectNode verify = json.createObjectNode().put("verified", true).put("handlingNote", "Đúng");
    MvcResult verified =
        perform(
            patch("/api/v1/dossiers/special-cases/" + id)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verify.toString()));
    assertThat(verified.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(verified, "/dossiers/special-cases/{id}", PathItem.HttpMethod.PATCH);
    assertThat(body(verified).at("/confirmedBy/id").asText()).isEqualTo(OFFICER.toString());
    MvcResult stale =
        perform(
            patch("/api/v1/dossiers/special-cases/" + id)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verify.toString()));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);

    USER_UNIT.set(UUID.randomUUID());
    assertThat(
            perform(get("/api/v1/dossiers/special-cases?period=" + year + "&unit=" + UNIT))
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(perform(get("/api/v1/dossiers/special-cases/" + id)).getResponse().getStatus())
        .isEqualTo(403);
    USER_UNIT.set(UNIT);
    assertThat(
            perform(get("/api/v1/dossiers/special-cases/" + UUID.randomUUID()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    UUID other = UUID.randomUUID();
    when(orgDirectory.assignmentsOf(eq(tenant), eq(other), any()))
        .thenReturn(List.of(assignment(other, UNIT, "CHINH", "2020-01-01", null)));
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
    UUID current = currentYear(today);
    PhanCongDaThayDoi side =
        PhanCongDaThayDoi.newBuilder()
            .setAssignmentId(UUID.randomUUID())
            .setPersonId(other)
            .setUnitId(OTHER_UNIT)
            .setPositionId(null)
            .setJobTitleId(UUID.randomUUID())
            .setKind("KIEM_NHIEM")
            .setIsPrimary(false)
            .setValidFrom(today.withDayOfYear(1))
            .setValidTo(null)
            .setSourceVersion(1L)
            .setAggregateVersion(1)
            .setIsHead(false)
            .build();
    SpecialCaseAssignmentConsumer consumer = assignmentConsumer();
    UUID eventId = UUID.randomUUID();
    assertThat(deliverAssignment(consumer, eventId, side)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(deliverAssignment(consumer, eventId, side))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(deliverAssignment(consumer, UUID.randomUUID(), side))
        .isEqualTo(InboxOutcome.PROCESSED);
    List<String> codes =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT case_code FROM dossier.special_case WHERE person_id = ?"
                            + " AND period_id = ?")
                    .params(other, current)
                    .query(String.class)
                    .list());
    assertThat(codes).containsExactly("KIEM_NHIEM");
  }

  private AssignmentView assignment(UUID person, UUID unit, String kind, String from, String to) {
    return new AssignmentView(
        UUID.randomUUID(),
        person,
        unit,
        null,
        UUID.randomUUID(),
        kind,
        "DANG",
        false,
        LocalDate.parse(from),
        to == null ? null : LocalDate.parse(to));
  }

  /** Kỳ năm chứa hôm nay: kỳ năm 2026 của bộ dữ liệu, hoặc một kỳ năm mới khi chạy năm khác. */
  private UUID currentYear(LocalDate today) throws Exception {
    if (today.getYear() == 2026) {
      return year;
    }
    UUID id = UUID.randomUUID();
    int bucket = today.getYear() * 10;
    insertPeriod(
        id,
        "Năm " + today.getYear(),
        today.getYear() + "-01-01",
        today.getYear() + "-12-31",
        bucket,
        "MO");
    inTenant(
        jdbc ->
            jdbc.sql("SELECT dossier.fn_create_period_partitions(?::regclass, ?)")
                .params("dossier.special_case", bucket)
                .query()
                .listOfRows());
    return id;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private SpecialCaseAssignmentConsumer assignmentConsumer() {
    org.springframework.kafka.core.KafkaOperations<String, byte[]> kafka =
        org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaOperations.class);
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new JdbcTemplate(dossierDataSource),
            dossierTm,
            "dossier",
            (topic, headers, data) -> decode(PhanCongDaThayDoi.class, data),
            new InboxRetryDlqPublisher(kafka),
            new InboxMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            new InboxProperties.Retry(null, null, null, null),
            Clock.systemUtc());
    org.springframework.beans.factory.ObjectProvider none =
        new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(
                org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class);
    return new SpecialCaseAssignmentConsumer(specialCases, processor, none, false);
  }

  private InboxOutcome deliverAssignment(
      SpecialCaseAssignmentConsumer consumer, UUID eventId, PhanCongDaThayDoi payload) {
    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(
            EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
            0,
            offset++,
            tenant.value() + ":" + eventId,
            encode(payload));
    header(record, OutboxHeaders.EVENT_ID, eventId.toString());
    header(record, OutboxHeaders.TENANT_ID, tenant.value().toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, "1");
    header(record, OutboxHeaders.CE_TYPE, "org.assignment.changed.v1");
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    return consumer.processor().process(consumer.endpoint(), record, () -> {});
  }

  private KySoDaHoanTat signed(UUID meeting, byte[] sha) {
    return KySoDaHoanTat.newBuilder()
        .setSignRequestId(UUID.randomUUID())
        .setPurpose("BIEN_BAN")
        .setSourceModule("dossier")
        .setSourceRefId(meeting)
        .setItems(List.of(new SignedItem(ByteBuffer.wrap(sha), null)))
        .setCertSha256(ByteBuffer.wrap(new byte[32]))
        .setSignedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
        .setAggregateVersion(1)
        .build();
  }

  private InboxOutcome deliver(
      InboxEndpoint endpoint,
      String topic,
      String type,
      UUID eventId,
      SpecificRecord payload,
      UUID actor) {
    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(topic, 0, offset++, tenant.value() + ":" + eventId, encode(payload));
    header(record, OutboxHeaders.EVENT_ID, eventId.toString());
    header(record, OutboxHeaders.TENANT_ID, tenant.value().toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, "1");
    header(record, OutboxHeaders.CE_TYPE, type);
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    header(record, OutboxHeaders.ACTOR_ID, actor.toString());
    return minutesConsumer.processor().process(endpoint, record, () -> {});
  }

  private static void header(ConsumerRecord<String, byte[]> record, String key, String value) {
    record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  private static byte[] encode(SpecificRecord payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<SpecificRecord>((Class<SpecificRecord>) payload.getClass())
          .write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private String status(UUID id) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT status FROM dossier.review_meeting WHERE id = ?")
                .param(id)
                .query(String.class)
                .single());
  }

  /** Hội nghị năm 2025 (đã ký) với một hạn chế đang mở của {@code subject}. */
  private UUID previousRemediationItem(UUID subject, String text) throws Exception {
    UUID meeting = UUID.randomUUID();
    UUID item = UUID.randomUUID();
    CommentSealer.Sealed sealed =
        TenantContext.call(
            tenant, () -> sealer.seal(tenant, CommentSealer.REMEDIATION_COLUMN, item, text));
    inTenant(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO dossier.review_meeting (id, created_by, updated_by, period_id,"
                      + " period_bucket, org_unit_id, meeting_type, scheduled_at, draft_sent_at,"
                      + " chair_person_id, secretary_person_id, status, minutes_sha256,"
                      + " sign_request_id, subject_person_id) VALUES (?, ?, ?, ?, 20250, ?,"
                      + " 'KIEM_DIEM_NAM', now() - interval '1 year', now() - interval '1 year',"
                      + " ?, ?, 'DA_KY', ?, ?, ?)")
              .params(
                  meeting,
                  OFFICER,
                  OFFICER,
                  locked,
                  UNIT,
                  CHAIR,
                  OUTSIDER,
                  new byte[32],
                  UUID.randomUUID(),
                  subject)
              .update();
          return jdbc.sql(
                  "INSERT INTO dossier.remediation_item (id, created_by, updated_by, period_id,"
                      + " period_bucket, person_id, meeting_id, content_enc, dek_id, status)"
                      + " VALUES (?, ?, ?, ?, 20250, ?, ?, ?, ?, 'MO')")
              .params(
                  item, OFFICER, OFFICER, locked, subject, meeting, sealed.cipher(), sealed.dekId())
              .update();
        });
    return item;
  }

  private MvcResult minutes(UUID id, String ifMatch, List<UUID> present, int htt) throws Exception {
    ObjectNode req = json.createObjectNode().put("meetingAt", "2027-01-14T08:05:00+07:00");
    var attendance = req.putArray("attendance");
    present.forEach(
        p -> attendance.addObject().put("personId", p.toString()).put("attendance", "CO_MAT"));
    req.putObject("vote").put("HTXSNV", 1).put("HTTNV", htt - 1).put("HTNV", 0).put("KHTNV", 0);
    return perform(
        post("/api/v1/dossiers/meetings/" + id + "/minutes")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(req.toString()));
  }

  private MvcResult close(UUID id, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/dossiers/meetings/" + id + "/close")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch));
  }

  private MvcResult remediation(UUID id, String ifMatch, String status) throws Exception {
    return perform(
        patch("/api/v1/dossiers/remediation-items/" + id)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.createObjectNode().put("status", status).toString()));
  }

  private void enableAnonymousComments() {
    JdbcClient jdbc = JdbcClient.create(configDataSource);
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .executeWithoutResult(
                    s ->
                        jdbc.sql(
                                "INSERT INTO config.tenant_setting (id, created_by, updated_by,"
                                    + " setting_key, scope_unit_id, value, value_schema) VALUES"
                                    + " (?, ?, ?, 'dossier.comment.anonymous', ?, 'true'::jsonb,"
                                    + " 'json.boolean') ON CONFLICT DO NOTHING")
                            .params(UUID.randomUUID(), OFFICER, OFFICER, UNIT)
                            .update()));
  }

  private MvcResult comment(UUID id, UUID subject, String kind, String phase, String content)
      throws Exception {
    ObjectNode req =
        json.createObjectNode()
            .put("subjectPersonId", subject.toString())
            .put("kind", kind)
            .put("phase", phase)
            .put("content", content);
    return perform(
        post("/api/v1/dossiers/meetings/" + id + "/comments")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(req.toString()));
  }

  private ObjectNode createBody(UUID period, UUID subject) {
    ObjectNode req =
        json.createObjectNode()
            .put("periodId", period.toString())
            .put("orgUnitId", UNIT.toString())
            .put("meetingType", "KIEM_DIEM_NAM")
            .put("scheduledAt", "2027-01-14T08:00:00+07:00")
            .put("location", "Phòng họp tầng 3")
            .put("chairPersonId", CHAIR.toString())
            .put("secretaryPersonId", SECRETARY.toString())
            .put("subjectPersonId", subject.toString());
    req.putArray("participantPersonIds").add(MEMBER.toString());
    return req;
  }

  private UUID createdId(UUID subject) throws Exception {
    MvcResult r = create(createBody(year, subject), UUID.randomUUID().toString());
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    return UUID.fromString(body(r).get("id").asText());
  }

  private MvcResult create(ObjectNode req, String key) throws Exception {
    return perform(
        post("/api/v1/dossiers/meetings")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(req.toString()));
  }

  private MvcResult update(UUID id, String ifMatch, String body) throws Exception {
    return perform(
        patch("/api/v1/dossiers/meetings/" + id)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private MvcResult send(UUID id, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/dossiers/meetings/" + id + "/send-draft")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch));
  }

  private void setDraftSentAt(UUID id, String at) throws Exception {
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE dossier.review_meeting SET draft_sent_at = CAST(? AS timestamptz)"
                        + " WHERE id = ?")
                .params(at, id)
                .update());
  }

  private void insertPeriod(
      UUID id, String name, String start, String end, int bucket, String status) {
    JdbcClient jdbc = JdbcClient.create(configDataSource);
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .executeWithoutResult(
                    s ->
                        jdbc.sql(
                                "INSERT INTO config.period (id, code, name, kind, flow,"
                                    + " start_date, end_date, period_bucket, status, locked_at,"
                                    + " created_by, updated_by) VALUES (?, ?, ?, 'NAM', 'DANG',"
                                    + " ?::date, ?::date, ?, ?, CASE WHEN ? = 'DA_KHOA' THEN now()"
                                    + " END, ?, ?)")
                            .params(
                                id,
                                "N-" + id.toString().substring(0, 8),
                                name,
                                start,
                                end,
                                bucket,
                                status,
                                status,
                                OFFICER,
                                OFFICER)
                            .update()));
  }

  private <T> T inTenant(java.util.function.Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dossierDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(dossierTm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    String user = USER.get().toString();
    return TenantContext.call(tenant, () -> mvc.perform(request.principal(() -> user)).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
