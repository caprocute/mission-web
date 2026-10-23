package vn.viettel.okrkpi.dossier.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.swagger.v3.oas.models.PathItem;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalStepEvents;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierSigningService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.FileHashes;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageSigner;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.dossier.infrastructure.listener.DossierSigningConsumer;
import vn.viettel.okrkpi.dossier.infrastructure.persistence.OutboxPackageEvents;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.web.InMemoryJobRegistry;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * Kiểm thử tích hợp CORE-06-07 trên PostgreSQL thật, cổng hồ sơ của MOD-04 thật và SVC-10 giả: danh
 * sách kiểm tra BR-11-09 (hồ sơ chưa ký số, băm tệp lệch, khiếu nại mở và tham số nộp kèm), ký gói
 * 202 khớp hợp đồng, EVT-18 nộp gói và khoá hồ sơ, mọi lệnh ghi sau {@code DA_NOP} trả 409 {@code
 * DOSSIER-409-PACKAGE-LOCKED}, EVT-22 lập phiên bản mới mà gói cũ giữ nguyên.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.dossier.package.jobs.enabled=false",
      "DB_DOSSIER_POOL_MAX=4"
    })
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@Import(DossierSigningApiIT.SigningConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DossierSigningApiIT extends ContractConformanceTest {

  static final UUID OFFICER = UUID.fromString("01927a06-0000-7000-8000-0000000007a7");
  static final int BUCKET = 20260;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  /**
   * SVC-10 giả: như SVC-10 thật, hỏi lại MOD-06 tài liệu cần ký ngay trong lời gọi tạo yêu cầu và
   * từ chối khi gói chưa sẵn sàng hoặc người gọi không phải người ký.
   */
  static final class FakeSigner implements PackageSigner {

    final List<UUID> requests = new CopyOnWriteArrayList<>();
    final List<byte[]> manifestHashes = new CopyOnWriteArrayList<>();
    volatile DossierSigningService signing;

    @Override
    public UUID request(
        TenantId tenant,
        UUID packageId,
        String provider,
        String idempotencyKey,
        String authorization) {
      DossierSigningService.SignSource source = signing.signSource(tenant, packageId).orElseThrow();
      if (!source.ready() || !source.signers().contains(OFFICER)) {
        throw new IllegalStateException("Tài liệu cần ký chưa sẵn sàng");
      }
      UUID id = UUID.randomUUID();
      requests.add(id);
      manifestHashes.add(source.manifestHash());
      return id;
    }
  }

  /** Băm tính lại từ kho đối tượng giả; khoá không có thì coi như kho không trả. */
  static final class FakeHashes implements FileHashes {

    final Map<String, byte[]> stored = new ConcurrentHashMap<>();

    @Override
    public Optional<byte[]> sha256(TenantId tenant, String objectKey) {
      return Optional.ofNullable(stored.get(objectKey));
    }
  }

  /** Tham số tenant giả: chỉ trả tham số nộp kèm khiếu nại khi bật. */
  static final class FakeSettings implements TenantSettingApi {

    volatile boolean complaintBypass;

    @Override
    public Optional<JsonNode> get(TenantId tenantId, String key, UUID unitId) {
      return complaintBypass && DossierSigningService.COMPLAINT_SETTING.equals(key)
          ? Optional.of(BooleanNode.TRUE)
          : Optional.empty();
    }
  }

  static final AtomicInteger STEPS = new AtomicInteger();
  static final FakeSigner SIGNER = new FakeSigner();
  static final FakeSettings SETTINGS = new FakeSettings();
  static final FakeHashes HASHES = new FakeHashes();

  /** Lớp thường nhập bằng {@code @Import}: {@code @TestConfiguration} lồng bị quét sang IT khác. */
  static class SigningConfig {

    @Bean
    @Primary
    PackageSigner testPackageSigner() {
      return SIGNER;
    }

    @Bean
    @Primary
    FileHashes testFileHashes() {
      return HASHES;
    }

    @Bean
    @Primary
    TenantSettingApi testTenantSettings() {
      return SETTINGS;
    }

    /** Môi trường kiểm thử không có Apicurio Registry: thay bộ tuần tự Avro bằng bản giả. */
    @Bean
    @Primary
    PackageEvents testPackageEvents(
        @Qualifier("dossierDataSource") DataSource dataSource, ObjectMapper json) {
      return new OutboxPackageEvents(
          new OutboxWriter(
              new JdbcTemplate(dataSource),
              "dossier",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()),
          json);
    }

    /** EVT-11 của lệnh khoá hồ sơ ở MOD-04: ghi nhận số bước, không tuần tự Avro. */
    @Bean
    @Primary
    AppraisalStepEvents testAppraisalStepEvents() {
      return (tenant, profileId, step, version) -> STEPS.incrementAndGet();
    }
  }

  @MockitoBean private DossierCaller caller;
  @MockitoBean private OrgDirectoryApi orgDirectory;
  @MockitoBean private PolicyQueryApi policies;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private DossierSigningService signing;
  @Autowired private DossierPackageRepository repository;
  private DossierSigningConsumer consumer;
  private final AtomicInteger offset = new AtomicInteger();

  @Autowired
  @Qualifier("dossierDataSource") private DataSource dossierDataSource;

  @Autowired
  @Qualifier("dossierTransactionManager") private PlatformTransactionManager dossierTm;

  @Autowired
  @Qualifier("appraisalDataSource") private DataSource appraisalDataSource;

  @Autowired
  @Qualifier("appraisalTransactionManager") private PlatformTransactionManager appraisalTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID year = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.DOSSIERS;
  }

  @BeforeEach
  void caller() {
    when(caller.currentUserId()).thenAnswer(inv -> Optional.of(OFFICER));
    SETTINGS.complaintBypass = false;
    SIGNER.signing = signing;
    HASHES.stored.clear();
  }

  @BeforeAll
  @SuppressWarnings({"unchecked", "rawtypes"})
  void setUp() throws Exception {
    org.springframework.kafka.core.KafkaOperations<String, byte[]> kafka =
        org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaOperations.class);
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new JdbcTemplate(dossierDataSource),
            dossierTm,
            "dossier",
            (topic, headers, data) ->
                decode(
                    EventTopics.EVT_18_KY_SO_DA_HOAN_TAT.equals(topic)
                        ? KySoDaHoanTat.class
                        : KyDaMoLaiHauKiem.class,
                    data),
            new InboxRetryDlqPublisher(kafka),
            new InboxMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            new InboxProperties.Retry(null, null, null, null),
            Clock.systemUtc());
    org.springframework.beans.factory.ObjectProvider none =
        new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(
                org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class);
    consumer = new DossierSigningConsumer(signing, processor, none, false);
    insertPeriod();
    inDossier(
        jdbc -> {
          for (String t : List.of("dossier_package", "form_instance", "special_case")) {
            jdbc.sql("SELECT dossier.fn_create_period_partitions(?::regclass, ?)")
                .params("dossier." + t, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
    inAppraisal(
        jdbc -> {
          for (String t : List.of("appraisal_profile", "score_snapshot", "rating_proposal")) {
            jdbc.sql("SELECT appraisal.fn_create_period_partitions(?::regclass, ?)")
                .params("appraisal." + t, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  // ---------------------------------------------------------------- danh sách kiểm tra

  @Test
  void hoSoChuaKySoThiDanhSachChuaDatVaKyTra409() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> profiles = insertProfiles(unit, List.of("DA_KY_SO", "DA_KY_SO", "DA_QUYET_DINH"));
    UUID pkg = insertPackage(unit, profiles);
    when(caller.currentUnitId()).thenReturn(Optional.of(unit));

    MvcResult checklist = perform(get("/api/v1/dossiers/packages/" + pkg + "/checklist"));
    assertThat(checklist.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(checklist, "/dossiers/packages/{id}/checklist", PathItem.HttpMethod.GET);
    JsonNode body = body(checklist);
    assertThat(body.get("passed").asBoolean()).isFalse();
    assertThat(item(body, "HO_SO_DA_KY_SO").get("status").asText()).isEqualTo("CANH_BAO");
    assertThat(item(body, "HO_SO_DA_KY_SO").get("count").asInt()).isEqualTo(1);
    assertThat(item(body, "CA_NHAN_DA_KY").get("count").asInt()).isEqualTo(1);
    assertThat(item(body, "MA_BAM_KHOP").get("status").asText()).isEqualTo("DAT");

    int before = SIGNER.requests.size();
    MvcResult refused = sign(pkg, etag(pkg));
    assertThat(refused.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(refused).get("code").asText()).isEqualTo("DOSSIER-409-CHECKLIST-NOT-PASSED");
    assertThat(SIGNER.requests).hasSize(before);
    assertThat(status(pkg)).isEqualTo("CHO_KY");

    when(caller.currentUnitId()).thenReturn(Optional.of(UUID.randomUUID()));
    assertThat(
            perform(get("/api/v1/dossiers/packages/" + pkg + "/checklist"))
                .getResponse()
                .getStatus())
        .isEqualTo(403);
  }

  @Test
  void bamTepLechVaKhieuNaiMoLamDanhSachChuaDat() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> profiles = insertProfiles(unit, List.of("DA_KY_SO", "DA_KY_SO", "KHIEU_NAI"));
    UUID pkg = insertPackage(unit, profiles);
    when(caller.currentUnitId()).thenReturn(Optional.of(unit));

    JsonNode body = body(perform(get("/api/v1/dossiers/packages/" + pkg + "/checklist")));
    assertThat(item(body, "KHONG_KHIEU_NAI_MO").get("status").asText()).isEqualTo("CANH_BAO");
    assertThat(item(body, "HO_SO_DA_KY_SO").get("status").asText()).isEqualTo("DAT");
    assertThat(body.get("passed").asBoolean()).isFalse();

    // Tham số tenant cho phép nộp kèm hồ sơ đang khiếu nại.
    SETTINGS.complaintBypass = true;
    body = body(perform(get("/api/v1/dossiers/packages/" + pkg + "/checklist")));
    assertThat(item(body, "KHONG_KHIEU_NAI_MO").get("status").asText()).isEqualTo("DAT");
    assertThat(body.get("passed").asBoolean()).isTrue();

    // Tệp trong kho bị thay: băm tính lại khác băm đã ghi.
    String key = objectKeys(pkg).get(0);
    HASHES.stored.put(key, sha("tep-bi-sua"));
    body = body(perform(get("/api/v1/dossiers/packages/" + pkg + "/checklist")));
    assertThat(item(body, "MA_BAM_KHOP").get("status").asText()).isEqualTo("CANH_BAO");
    assertThat(item(body, "MA_BAM_KHOP").get("count").asInt()).isEqualTo(1);
    assertThat(body.get("passed").asBoolean()).isFalse();
    MvcResult refused = sign(pkg, etag(pkg));
    assertThat(refused.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(refused).get("code").asText()).isEqualTo("DOSSIER-409-CHECKLIST-NOT-PASSED");
  }

  // ---------------------------------------------------------------- ký, nộp, khoá, mở lại

  @Test
  void kyNopKhoaGoiVaMoLaiLapPhienBanMoi() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> profiles = insertProfiles(unit, List.of("DA_KY_SO", "DA_KY_SO"));
    UUID pkg = insertPackage(unit, profiles);
    when(caller.currentUnitId()).thenReturn(Optional.of(unit));

    assertThat(sign(pkg, "\"99\"").getResponse().getStatus()).isEqualTo(409);
    MvcResult accepted = sign(pkg, etag(pkg));
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(accepted, "/dossiers/packages/{id}/sign", PathItem.HttpMethod.POST);
    UUID request = UUID.fromString(body(accepted).get("signRequestId").asText());
    assertThat(SIGNER.requests).contains(request);
    assertThat(accepted.getResponse().getHeader("Location")).endsWith("/api/v1/jobs/" + request);

    // Manifest JCS đã chốt: một mục cho mỗi tệp, băm manifest là băm SHA-256 của chuỗi chuẩn tắc.
    Map<String, Object> row = packageRow(pkg);
    byte[] manifestHash = (byte[]) row.get("manifest_hash");
    String manifest = row.get("manifest").toString();
    JsonNode entries = json.readTree(manifest);
    assertThat(entries).hasSize(3);
    assertThat(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical(pkg).getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(manifestHash);
    assertThat(SIGNER.manifestHashes.get(SIGNER.manifestHashes.size() - 1)).isEqualTo(manifestHash);
    assertThat(row.get("sign_request_id")).isEqualTo(request);
    assertThat(jobStatus(request)).isEqualTo("DANG_CHAY");

    // EVT-18 có băm lệch manifest bị bỏ qua.
    assertThat(deliverSigned(request, pkg, sha("khac"))).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(status(pkg)).isEqualTo("CHO_KY");

    int steps = STEPS.get();
    assertThat(deliverSigned(request, pkg, manifestHash)).isEqualTo(InboxOutcome.PROCESSED);
    row = packageRow(pkg);
    assertThat(row.get("status")).isEqualTo("DA_NOP");
    assertThat(row.get("submitted_at")).isNotNull();
    assertThat((byte[]) row.get("package_sha256")).isEqualTo(manifestHash);
    long submittedVersion = ((Number) row.get("row_version")).longValue();
    long notices =
        inDossier(
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM dossier.outbox_event WHERE aggregate_id = ?"
                            + " AND topic = ?")
                    .params(pkg, EventTopics.EVT_21_YEU_CAU_THONG_BAO)
                    .query(Long.class)
                    .single());
    assertThat(notices).isEqualTo(1L);
    assertThat(profileStatuses(profiles)).containsOnly("DA_KHOA");
    assertThat(STEPS.get()).isGreaterThanOrEqualTo(steps);
    assertThat(jobStatus(request)).isEqualTo("HOAN_TAT");

    // EVT-18 lặp (mã sự kiện khác) không đổi gì.
    assertThat(deliverSigned(request, pkg, manifestHash)).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(((Number) packageRow(pkg).get("row_version")).longValue())
        .isEqualTo(submittedVersion);

    // Mọi lệnh ghi lên gói đã nộp trả 409 DOSSIER-409-PACKAGE-LOCKED.
    String etag = etag(pkg);
    MvcResult signAgain = sign(pkg, etag);
    assertThat(signAgain.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(signAgain).get("code").asText()).isEqualTo("DOSSIER-409-PACKAGE-LOCKED");
    MvcResult regenerate =
        perform(
            post("/api/v1/dossiers/packages/" + pkg + "/regenerate")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    assertThat(regenerate.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(regenerate).get("code").asText()).isEqualTo("DOSSIER-409-PACKAGE-LOCKED");
    // Lớp lưu trữ: trigger đóng băng chặn mọi UPDATE, đổi sang DOSSIER-409-PACKAGE-LOCKED.
    TransactionTemplate tx = new TransactionTemplate(dossierTm);
    DossierPackageRepository.PackageRow submitted =
        TenantContext.call(
            tenant, () -> tx.execute(s -> repository.find(tenant, pkg).orElseThrow()));
    assertThatThrownBy(
            () ->
                TenantContext.call(
                    tenant,
                    () ->
                        tx.execute(
                            s ->
                                repository.updateStatus(
                                    tenant, submitted, PackageStatus.DANG_LAP, null, OFFICER))))
        .isInstanceOf(BusinessException.class)
        .hasMessage("DOSSIER-409-PACKAGE-LOCKED");

    // EVT-22 phạm vi đơn vị khác: không lập phiên bản.
    assertThat(deliverReopened("DON_VI", List.of(UUID.randomUUID())))
        .isEqualTo(InboxOutcome.PROCESSED);
    assertThat(versionsOf(unit)).hasSize(1);

    // EVT-22 phạm vi hồ sơ trong đơn vị: phiên bản 2 ở DANG_LAP, cùng mã; gói cũ giữ nguyên.
    assertThat(deliverReopened("HO_SO", List.of(profiles.get(0))))
        .isEqualTo(InboxOutcome.PROCESSED);
    List<Map<String, Object>> versions = versionsOf(unit);
    assertThat(versions).hasSize(2);
    assertThat(versions.get(0).get("id")).isEqualTo(pkg);
    assertThat(versions.get(0).get("status")).isEqualTo("DA_NOP");
    assertThat(((Number) versions.get(0).get("row_version")).longValue())
        .isEqualTo(submittedVersion);
    assertThat((byte[]) versions.get(0).get("package_sha256")).isEqualTo(manifestHash);
    assertThat(versions.get(1).get("version_no")).isEqualTo(2);
    assertThat(versions.get(1).get("status")).isEqualTo("DANG_LAP");
    assertThat(versions.get(1).get("code")).isEqualTo(versions.get(0).get("code"));

    // EVT-22 lặp cho cả kỳ: gói cũ đã có phiên bản mới hơn nên không lập thêm.
    assertThat(deliverReopened("TOAN_KY", List.of())).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(versionsOf(unit)).hasSize(2);
  }

  // ---------------------------------------------------------------- tiện ích

  private static JsonNode item(JsonNode checklist, String code) {
    for (JsonNode i : checklist.get("items")) {
      if (code.equals(i.get("code").asText())) {
        return i;
      }
    }
    throw new AssertionError("Không có mục " + code);
  }

  private List<UUID> insertProfiles(UUID unit, List<String> statuses) {
    List<UUID> ids = new ArrayList<>();
    statuses.forEach(s -> ids.add(UUID.randomUUID()));
    inAppraisal(
        jdbc -> {
          for (int i = 0; i < ids.size(); i++) {
            jdbc.sql(
                    "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by,"
                        + " period_id, period_bucket, person_id, flow, org_unit_id, assignment_id,"
                        + " job_title_id, is_head, policy_pack_version_id, status, final_rating_code,"
                        + " decided_at) VALUES (?, ?, ?, ?, ?, ?, 'DANG', ?, ?, ?, ?, ?, ?, 'HTTNV',"
                        + " now())")
                .params(
                    ids.get(i),
                    OFFICER,
                    OFFICER,
                    year,
                    BUCKET,
                    UUID.randomUUID(),
                    unit,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    i == 0,
                    UUID.randomUUID(),
                    statuses.get(i))
                .update();
          }
          return null;
        });
    return ids;
  }

  /** Gói {@code CHO_KY} với một bảng chấm điểm cho mỗi hồ sơ và Mẫu 3 của đơn vị, đều đã sinh. */
  private UUID insertPackage(UUID unit, List<UUID> profiles) throws Exception {
    UUID pkg = UUID.randomUUID();
    String code = "DP-2026-N-" + pkg.toString().substring(0, 4);
    inDossier(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO dossier.dossier_package (id, created_by, updated_by, period_id,"
                      + " period_bucket, code, org_unit_id, package_kind, status)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, 'NAM', 'CHO_KY')")
              .params(pkg, OFFICER, OFFICER, year, BUCKET, code, unit)
              .update();
          List<String[]> forms = new ArrayList<>();
          for (UUID p : profiles) {
            forms.add(new String[] {p.toString(), "BANG_CHAM_DIEM"});
          }
          forms.add(new String[] {profiles.get(0).toString(), "MAU_3"});
          for (String[] f : forms) {
            UUID id = UUID.randomUUID();
            byte[] sha = sha(id.toString());
            jdbc.sql(
                    "INSERT INTO dossier.form_instance (id, created_by, updated_by, period_id,"
                        + " period_bucket, dossier_package_id, profile_id, form_code,"
                        + " template_version, data_snapshot_hash, object_key, pdf_sha256, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, '1', ?, ?, ?, 'DA_SINH')")
                .params(
                    id,
                    OFFICER,
                    OFFICER,
                    year,
                    BUCKET,
                    pkg,
                    UUID.fromString(f[0]),
                    f[1],
                    sha("du-lieu-" + id),
                    "dossier/forms/" + id + "/" + HexFormat.of().formatHex(sha) + ".pdf",
                    sha)
                .update();
          }
          return null;
        });
    return pkg;
  }

  private void insertPeriod() {
    JdbcClient jdbc = JdbcClient.create(configDataSource);
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .executeWithoutResult(
                    s ->
                        jdbc.sql(
                                "INSERT INTO config.period (id, code, name, kind, flow,"
                                    + " start_date, end_date, period_bucket, status, created_by,"
                                    + " updated_by) VALUES (?, ?, 'Năm 2026', 'NAM', 'DANG',"
                                    + " date '2026-01-01', date '2026-12-31', ?, 'MO', ?, ?)")
                            .params(
                                year,
                                "N-" + year.toString().substring(0, 8),
                                BUCKET,
                                OFFICER,
                                OFFICER)
                            .update()));
  }

  /** Chuỗi manifest chuẩn tắc tính độc lập từ bảng biểu mẫu (khoá xếp, mục xếp theo mã). */
  private String canonical(UUID pkg) throws Exception {
    List<Map<String, Object>> forms =
        inDossier(
            jdbc ->
                jdbc.sql(
                        "SELECT id, form_code, profile_id, object_key, pdf_sha256"
                            + " FROM dossier.form_instance WHERE dossier_package_id = ? ORDER BY id")
                    .param(pkg)
                    .query()
                    .listOfRows());
    StringBuilder out = new StringBuilder("[");
    for (Map<String, Object> f : forms) {
      if (out.length() > 1) {
        out.append(',');
      }
      out.append("{\"bieuMauId\":\"")
          .append(f.get("id"))
          .append("\",\"hoSoId\":\"")
          .append(f.get("profile_id"))
          .append("\",\"khoaDoiTuong\":\"")
          .append(f.get("object_key"))
          .append("\",\"maMau\":\"")
          .append(f.get("form_code"))
          .append("\",\"sha256\":\"")
          .append(HexFormat.of().formatHex((byte[]) f.get("pdf_sha256")))
          .append("\"}");
    }
    return out.append(']').toString();
  }

  private List<String> objectKeys(UUID pkg) throws Exception {
    return inDossier(
        jdbc ->
            jdbc.sql(
                    "SELECT object_key FROM dossier.form_instance WHERE dossier_package_id = ?"
                        + " ORDER BY id")
                .param(pkg)
                .query(String.class)
                .list());
  }

  private Map<String, Object> packageRow(UUID pkg) throws Exception {
    return inDossier(
        jdbc ->
            jdbc.sql(
                    "SELECT status, row_version, manifest::text AS manifest, manifest_hash,"
                        + " package_sha256, submitted_at, sign_request_id"
                        + " FROM dossier.dossier_package WHERE id = ?")
                .param(pkg)
                .query()
                .singleRow());
  }

  private String status(UUID pkg) throws Exception {
    return packageRow(pkg).get("status").toString();
  }

  private List<Map<String, Object>> versionsOf(UUID unit) throws Exception {
    return inDossier(
        jdbc ->
            jdbc.sql(
                    "SELECT id, code, version_no, status, row_version, package_sha256"
                        + " FROM dossier.dossier_package WHERE org_unit_id = ? ORDER BY version_no")
                .param(unit)
                .query()
                .listOfRows());
  }

  private List<String> profileStatuses(List<UUID> ids) {
    return inAppraisal(
        jdbc ->
            jdbc.sql("SELECT status FROM appraisal.appraisal_profile WHERE id = ANY(?)")
                .param(ids.toArray(UUID[]::new))
                .query(String.class)
                .list());
  }

  private String etag(UUID pkg) throws Exception {
    return perform(get("/api/v1/dossiers/packages/" + pkg)).getResponse().getHeader("ETag");
  }

  private String jobStatus(UUID request) throws Exception {
    InMemoryJobRegistry.setActorOverride(ActorId.of(OFFICER));
    MvcResult job;
    try {
      job = perform(get("/api/v1/jobs/" + request));
    } finally {
      InMemoryJobRegistry.clearActorOverride();
    }
    assertThat(job.getResponse().getStatus()).isEqualTo(200);
    return body(job).get("status").asText();
  }

  private MvcResult sign(UUID pkg, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/dossiers/packages/" + pkg + "/sign")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  private static byte[] sha(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private InboxOutcome deliverSigned(UUID request, UUID pkg, byte[] hash) {
    KySoDaHoanTat payload =
        KySoDaHoanTat.newBuilder()
            .setSignRequestId(request)
            .setPurpose("GOI_HO_SO")
            .setSourceModule("dossier")
            .setSourceRefId(pkg)
            .setItems(
                List.of(
                    SignedItem.newBuilder()
                        .setDocumentSha256(ByteBuffer.wrap(hash))
                        .setSignedObjectKey("esign/signed/" + request + ".p7s")
                        .build()))
            .setCertSha256(ByteBuffer.wrap(sha("chung-thu")))
            .setSignedAt(Instant.now())
            .setAggregateVersion(1)
            .build();
    return consumer
        .processor()
        .process(
            consumer.signedEndpoint(),
            record(
                EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
                "esign.signature.completed.v1",
                UUID.randomUUID(),
                payload),
            () -> {});
  }

  private InboxOutcome deliverReopened(String scope, List<UUID> scopeIds) {
    KyDaMoLaiHauKiem payload =
        KyDaMoLaiHauKiem.newBuilder()
            .setPeriodId(year)
            .setReopenRequestId(UUID.randomUUID())
            .setScope(scope)
            .setScopeIds(scopeIds)
            .setApproverIds(List.of(OFFICER))
            .setExecutedAt(Instant.now())
            .setAggregateVersion(1)
            .build();
    return consumer
        .processor()
        .process(
            consumer.reopenedEndpoint(),
            record(
                EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
                "oversight.period.reopened.v1",
                UUID.randomUUID(),
                payload),
            () -> {});
  }

  private ConsumerRecord<String, byte[]> record(
      String topic, String type, UUID eventId, SpecificRecord payload) {
    ConsumerRecord<String, byte[]> record =
        new ConsumerRecord<>(
            topic, 0, offset.getAndIncrement(), tenant.value() + ":" + eventId, encode(payload));
    header(record, OutboxHeaders.EVENT_ID, eventId.toString());
    header(record, OutboxHeaders.TENANT_ID, tenant.value().toString());
    header(record, OutboxHeaders.AGGREGATE_VERSION, "1");
    header(record, OutboxHeaders.CE_TYPE, type);
    header(record, OutboxHeaders.CLASSIFICATION, Classification.C1.name());
    header(record, OutboxHeaders.OCCURRED_AT, Instant.now().toString());
    header(record, OutboxHeaders.ACTOR_ID, OFFICER.toString());
    return record;
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

  private <T> T inDossier(java.util.function.Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dossierDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(dossierTm).execute(s -> work.apply(jdbc)));
  }

  private <T> T inAppraisal(java.util.function.Function<JdbcClient, T> work) {
    JdbcClient jdbc = JdbcClient.create(appraisalDataSource);
    try {
      return TenantContext.call(
          tenant, () -> new TransactionTemplate(appraisalTm).execute(s -> work.apply(jdbc)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    String user = OFFICER.toString();
    return TenantContext.call(tenant, () -> mvc.perform(request.principal(() -> user)).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
