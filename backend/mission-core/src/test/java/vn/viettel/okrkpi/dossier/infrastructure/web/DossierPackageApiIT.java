package vn.viettel.okrkpi.dossier.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.RenderRequests;
import vn.viettel.okrkpi.dossier.infrastructure.listener.DossierPackageConsumer;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
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
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.appraisal.v1.XepLoaiDaQuyetDinh;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;

/**
 * Kiểm thử tích hợp CORE-06-06 trên PostgreSQL thật, cổng hồ sơ của MOD-04 thật ({@code
 * appraisal.appraisal_profile}) và SVC-11 giả: 1.000 EVT-12 đồng thời của một đơn vị chỉ lập một
 * gói và một lệnh sinh; tệp lỗi đưa gói về {@code DANG_LAP}, sinh lại chỉ gửi tệp lỗi; EVT-17 trùng
 * không đổi băm; năm thao tác của {@code dossiers.yaml} khớp hợp đồng; dữ liệu chuẩn tắc khớp băm;
 * đo xử lý EVT-17 theo lô (số lượng đặt bằng {@code -Ddossier.perf.events}, mặc định 5.000).
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.dossier.package.jobs.enabled=false",
      "mission.dossier.package.failure-grace=PT0S",
      "DB_DOSSIER_POOL_MAX=4"
    })
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@Import(DossierPackageApiIT.RenderConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DossierPackageApiIT extends ContractConformanceTest {

  static final UUID OFFICER = UUID.fromString("01927a06-0000-7000-8000-0000000006a7");
  static final UUID OTHER_UNIT = UUID.fromString("01927a06-0000-7000-8000-0000000006f9");
  static final int BUCKET = 20260;
  static final String PACK_CODE = "PP-DANG";
  static final String PACK_VERSION = "1.3.0";

  static final ThreadLocal<UUID> USER_UNIT = new ThreadLocal<>();

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  /**
   * SVC-11 giả: ghi lệnh, hỏi lại MOD-06 danh sách biểu mẫu như SVC-11 thật, trả trạng thái lệnh.
   */
  static final class FakeRender implements RenderRequests {

    final AtomicInteger requests = new AtomicInteger();
    final List<List<UUID>> sent = new CopyOnWriteArrayList<>();
    final Map<UUID, RequestState> states = new ConcurrentHashMap<>();
    volatile boolean failing;
    volatile DossierPackageService service;

    @Override
    public UUID request(TenantId tenant, UUID packageId, String idempotencyKey) {
      if (failing) {
        throw new RenderUnavailableException("SVC-11 giả đang lỗi", null);
      }
      requests.incrementAndGet();
      List<UUID> forms =
          service.renderInputs(tenant, null, packageId, List.of()).orElseThrow().stream()
              .map(DossierPackageService.RenderInput::formInstanceId)
              .toList();
      sent.add(forms);
      return UUID.randomUUID();
    }

    @Override
    public Optional<RequestState> state(TenantId tenant, UUID requestId) {
      return Optional.ofNullable(states.get(requestId));
    }
  }

  static final FakeRender RENDER = new FakeRender();

  /** Lớp thường nhập bằng {@code @Import}: {@code @TestConfiguration} lồng bị quét sang IT khác. */
  static class RenderConfig {

    @Bean
    @Primary
    RenderRequests testRenderRequests() {
      return RENDER;
    }
  }

  @MockitoBean private DossierCaller caller;
  @MockitoBean private OrgDirectoryApi orgDirectory;
  @MockitoBean private PolicyQueryApi policies;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private DossierPackageService service;
  @Autowired private DossierProfilesApi profiles;
  private DossierPackageConsumer consumer;
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
    USER_UNIT.remove();
    when(caller.currentUserId()).thenAnswer(inv -> Optional.of(OFFICER));
    when(caller.currentUnitId()).thenAnswer(inv -> Optional.ofNullable(USER_UNIT.get()));
    when(policies.resolve(any(), any(), any(), any(), any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new PolicyQueryApi.ResolvedPack(
                        UUID.randomUUID(),
                        PACK_CODE,
                        UUID.randomUUID(),
                        PACK_VERSION,
                        "hash",
                        null,
                        UUID.randomUUID(),
                        true)));
    when(policies.parameter(any(), any(), eq(DossierProfilesApi.PERSONAL_FORMS_PARAM)))
        .thenReturn(Optional.of(List.of("BANG_CHAM_DIEM")));
    when(policies.parameter(any(), any(), eq(DossierProfilesApi.UNIT_FORMS_PARAM)))
        .thenReturn(Optional.of(List.of("MAU_3")));
    when(policies.parameter(any(), any(), eq(DossierProfilesApi.TEMPLATE_VERSIONS_PARAM)))
        .thenReturn(Optional.of(Map.of("MAU_3", 2)));
    RENDER.failing = false;
    RENDER.service = service;
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
                    EventTopics.EVT_17_HO_SO_SO_DA_SINH.equals(topic)
                        ? HoSoSoDaSinh.class
                        : XepLoaiDaQuyetDinh.class,
                    data),
            new InboxRetryDlqPublisher(kafka),
            new InboxMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            new InboxProperties.Retry(null, null, null, null),
            Clock.systemUtc());
    org.springframework.beans.factory.ObjectProvider none =
        new org.springframework.beans.factory.support.StaticListableBeanFactory()
            .getBeanProvider(
                org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class);
    consumer = new DossierPackageConsumer(service, processor, none, false);
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

  // ---------------------------------------------------------------- EVT-12

  @Test
  void nghinEvt12DongThoiCuaMotDonViChiLapMotGoiVaMotLenhSinh() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> ids = insertProfiles(unit, 1_000, "CHO_QUYET_DINH");
    int before = RENDER.requests.get();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Future<InboxOutcome>> results = new ArrayList<>();
    for (UUID id : ids) {
      results.add(
          pool.submit(
              () -> {
                decide(id);
                return deliverDecided(id, UUID.randomUUID());
              }));
    }
    for (Future<InboxOutcome> f : results) {
      assertThat(f.get(5, TimeUnit.MINUTES)).isEqualTo(InboxOutcome.PROCESSED);
    }
    pool.shutdown();

    List<Map<String, Object>> packages = packagesOf(unit);
    assertThat(packages).hasSize(1);
    assertThat(packages.get(0).get("code").toString()).matches("^DP-2026-N-\\d{4}$");
    assertThat(packages.get(0).get("status")).isEqualTo("DANG_SINH");
    UUID pkg = (UUID) packages.get(0).get("id");
    assertThat(count(pkg, "BANG_CHAM_DIEM")).isEqualTo(1_000);
    assertThat(count(pkg, "MAU_3")).isEqualTo(1);
    assertThat(RENDER.requests.get() - before).isEqualTo(1);
    assertThat(RENDER.sent.get(RENDER.sent.size() - 1)).hasSize(1_001);

    // EVT-12 lặp với mã sự kiện khác không thêm gói, không thêm biểu mẫu, không gửi lệnh mới.
    assertThat(deliverDecided(ids.get(0), UUID.randomUUID())).isEqualTo(InboxOutcome.PROCESSED);
    assertThat(packagesOf(unit)).hasSize(1);
    assertThat(count(pkg, null)).isEqualTo(1_001);
    assertThat(RENDER.requests.get() - before).isEqualTo(1);
    assertThat(
            jdbcDossier(
                "SELECT count(DISTINCT template_version) FROM dossier.form_instance"
                    + " WHERE dossier_package_id = ? AND form_code = 'MAU_3' AND template_version = '2'",
                pkg))
        .isEqualTo(1L);
  }

  @Test
  void conHoSoChuaQuyetDinhThiChuaSinhTruongHopDacThuDuocTinhLaDaXuLy() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> ids = insertProfiles(unit, 3, "CHO_QUYET_DINH");
    decide(ids.get(0));
    decide(ids.get(1));
    deliverDecided(ids.get(0), UUID.randomUUID());
    UUID pkg = (UUID) packagesOf(unit).get(0).get("id");
    assertThat(packagesOf(unit).get(0).get("status")).isEqualTo("DANG_LAP");
    assertThat(count(pkg, "BANG_CHAM_DIEM")).isEqualTo(1);

    UUID person = personOf(ids.get(2));
    inDossier(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO dossier.special_case (id, created_by, updated_by, period_id,"
                        + " period_bucket, person_id, case_code, detected_by, effect, legal_ref)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'PL6_1', 'THU_CONG', 'KHONG_XEP_LOAI', 'PL 6')")
                .params(UUID.randomUUID(), OFFICER, OFFICER, year, BUCKET, person)
                .update());
    deliverDecided(ids.get(1), UUID.randomUUID());
    assertThat(packagesOf(unit).get(0).get("status")).isEqualTo("DANG_SINH");
    assertThat(count(pkg, "BANG_CHAM_DIEM")).isEqualTo(2);
    assertThat(count(pkg, "MAU_3")).isEqualTo(1);
  }

  // ---------------------------------------------------------------- tệp lỗi, sinh lại, EVT-17

  @Test
  void tepLoiDuaGoiVeDangLapSinhLaiChiGuiTepLoiVaEvt17TrungKhongDoiBam() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> ids = insertProfiles(unit, 3, "DA_QUYET_DINH");
    deliverDecided(ids.get(0), UUID.randomUUID());
    UUID pkg = (UUID) packagesOf(unit).get(0).get("id");
    assertThat(packagesOf(unit).get(0).get("status")).isEqualTo("DANG_SINH");
    List<UUID> forms = formIds(pkg);
    assertThat(forms).hasSize(4);
    UUID request = requestOf(forms.get(0));
    assertThat(request).isNotNull();

    byte[] first = sha("tep-1");
    for (int i = 0; i < 3; i++) {
      assertThat(
              deliverGenerated(forms.get(i), UUID.randomUUID(), i == 0 ? first : sha("tep-" + i)))
          .isEqualTo(InboxOutcome.PROCESSED);
    }
    // EVT-17 trùng (mã sự kiện khác, băm khác) không đổi băm đã ghi.
    assertThat(deliverGenerated(forms.get(0), UUID.randomUUID(), sha("khac")))
        .isEqualTo(InboxOutcome.PROCESSED);
    service.settle();
    assertThat(pdfSha(forms.get(0))).isEqualTo(first);
    assertThat(packagesOf(unit).get(0).get("status")).isEqualTo("DANG_SINH");

    // SVC-11 báo lệnh đã kết thúc mà còn tệp chưa sinh: tệp đó lỗi, gói về DANG_LAP.
    RENDER.states.put(
        request, new RenderRequests.RequestState(true, Instant.now().minusSeconds(60)));
    assertThat(TenantContext.call(tenant, () -> service.track(tenant, 50))).isEqualTo(1);
    assertThat(packagesOf(unit).get(0).get("status")).isEqualTo("DANG_LAP");
    USER_UNIT.set(unit);
    MvcResult failed = perform(get("/api/v1/dossiers/packages/" + pkg + "/forms?status=LOI"));
    assertThat(failed.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(failed, "/dossiers/packages/{id}/forms", PathItem.HttpMethod.GET);
    assertThat(body(failed).get("items")).hasSize(1);
    UUID failedForm = UUID.fromString(body(failed).at("/items/0/id").asText());
    assertThat(body(failed).at("/items/0/renderStatus").asText()).isEqualTo("LOI");

    MvcResult got = perform(get("/api/v1/dossiers/packages/" + pkg));
    String etag = got.getResponse().getHeader("ETag");
    assertThat(body(got).at("/formCounts/failed").asInt()).isEqualTo(1);
    assertThat(body(got).at("/formCounts/rendered").asInt()).isEqualTo(3);

    MvcResult stale = regenerate(pkg, "\"0\"");
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    int before = RENDER.requests.get();
    MvcResult accepted = regenerate(pkg, etag);
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    assertResponseConforms(
        accepted, "/dossiers/packages/{id}/regenerate", PathItem.HttpMethod.POST);
    assertThat(accepted.getResponse().getHeader("Location")).endsWith("/api/v1/jobs/" + pkg);
    // Lệnh sinh xếp hàng sau giao dịch của API, tác vụ định kỳ (tắt trong kiểm thử) gửi đi.
    service.settle();
    assertThat(RENDER.requests.get() - before).isEqualTo(1);
    assertThat(RENDER.sent.get(RENDER.sent.size() - 1)).containsExactly(failedForm);
    assertThat(pdfSha(forms.get(0))).isEqualTo(first);

    MvcResult again =
        regenerate(
            pkg, perform(get("/api/v1/dossiers/packages/" + pkg)).getResponse().getHeader("ETag"));
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("DOSSIER-409-INVALID-STATE");

    deliverGenerated(failedForm, UUID.randomUUID(), sha("tep-lai"));
    assertThat(service.settle()).isEqualTo(1);
    assertThat(packagesOf(unit).get(0).get("status")).isEqualTo("CHO_KY");
    MvcResult none =
        regenerate(
            pkg, perform(get("/api/v1/dossiers/packages/" + pkg)).getResponse().getHeader("ETag"));
    assertThat(body(none).get("code").asText()).isEqualTo("DOSSIER-409-NO-FAILED-FORMS");
  }

  @Test
  void svc11KhongNhanLenhThiTepChoSangLoiVaGoiVeDangLap() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> ids = insertProfiles(unit, 2, "DA_QUYET_DINH");
    RENDER.failing = true;
    deliverDecided(ids.get(0), UUID.randomUUID());
    UUID pkg = (UUID) packagesOf(unit).get(0).get("id");
    assertThat(packagesOf(unit).get(0).get("status")).isEqualTo("DANG_LAP");
    assertThat(
            jdbcDossier(
                "SELECT count(*) FROM dossier.form_instance WHERE dossier_package_id = ?"
                    + " AND status = 'LOI'",
                pkg))
        .isEqualTo(3L);
  }

  // ---------------------------------------------------------------- API

  @Test
  void lapThuCongXemDanhSachKhopHopDongVaPhamVi() throws Exception {
    UUID unit = UUID.randomUUID();
    USER_UNIT.set(unit);
    MvcResult undecided = createPackage(unit, "NAM", UUID.randomUUID().toString());
    assertThat(undecided.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(undecided).get("code").asText()).isEqualTo("DOSSIER-409-PERIOD-NOT-DECIDED");

    insertProfiles(unit, 1, "DA_QUYET_DINH");
    insertProfiles(unit, 1, "CHO_QUYET_DINH");
    String key = UUID.randomUUID().toString();
    MvcResult created = createPackage(unit, "NAM", key);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/dossiers/packages", PathItem.HttpMethod.POST);
    JsonNode body = body(created);
    UUID id = UUID.fromString(body.get("id").asText());
    assertThat(created.getResponse().getHeader("Location")).endsWith("/dossiers/packages/" + id);
    assertThat(body.get("status").asText()).isEqualTo("DANG_LAP");
    assertThat(body.at("/formCounts/total").asInt()).isEqualTo(1);
    assertThat(body.get("profileCount").asInt()).isEqualTo(1);
    assertThat(body.at("/period/label").asText()).isEqualTo("Năm 2026");

    assertThat(body(createPackage(unit, "NAM", key)).get("id").asText()).isEqualTo(id.toString());
    MvcResult dup = createPackage(unit, "NAM", UUID.randomUUID().toString());
    assertThat(dup.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(dup).get("code").asText()).isEqualTo("DOSSIER-409-PACKAGE-EXISTS");
    assertThat(createPackage(unit, "QUY", UUID.randomUUID().toString()).getResponse().getStatus())
        .isEqualTo(422);

    MvcResult got = perform(get("/api/v1/dossiers/packages/" + id));
    assertThat(got.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(got, "/dossiers/packages/{id}", PathItem.HttpMethod.GET);
    MvcResult page = perform(get("/api/v1/dossiers/packages?period=" + year + "&limit=1"));
    assertThat(page.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(page, "/dossiers/packages", PathItem.HttpMethod.GET);
    assertThat(body(page).at("/items/0/id").asText()).isEqualTo(id.toString());
    assertThat(perform(get("/api/v1/dossiers/packages?status=SAI")).getResponse().getStatus())
        .isEqualTo(422);

    USER_UNIT.set(OTHER_UNIT);
    assertThat(perform(get("/api/v1/dossiers/packages/" + id)).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(body(perform(get("/api/v1/dossiers/packages?period=" + year))).get("items"))
        .isEmpty();
    assertThat(createPackage(unit, "NAM", UUID.randomUUID().toString()).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(
            perform(get("/api/v1/dossiers/packages/" + UUID.randomUUID()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void duLieuChuanTacKhopBamVaCongNoiBoChiTraBieuMauCho() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> ids = insertProfiles(unit, 2, "DA_QUYET_DINH");
    deliverDecided(ids.get(0), UUID.randomUUID());
    UUID pkg = (UUID) packagesOf(unit).get(0).get("id");
    for (UUID form : formIds(pkg)) {
      MvcResult data = perform(get("/internal/v1/dossier-form-instances/" + form + "/render-data"));
      assertThat(data.getResponse().getStatus()).isEqualTo(200);
      byte[] bytes = data.getResponse().getContentAsByteArray();
      assertThat(MessageDigest.getInstance("SHA-256").digest(bytes)).isEqualTo(snapshotHash(form));
      JsonNode doc = json.readTree(bytes);
      assertThat(doc.at("/boQuyTac/ma").asText()).isEqualTo(PACK_CODE);
    }
    MvcResult inputs =
        perform(get("/internal/v1/dossier-render-inputs/" + pkg + "?formCodes=MAU_3"));
    assertThat(inputs.getResponse().getStatus()).isEqualTo(200);
    JsonNode forms = body(inputs).get("forms");
    assertThat(forms).hasSize(1);
    assertThat(forms.get(0).get("templateVersion").asInt()).isEqualTo(2);
    assertThat(forms.get(0).get("policyPackVersion").asText()).isEqualTo(PACK_VERSION);
    assertThat(forms.get(0).get("dataSnapshotHash").asText()).matches("^[0-9a-f]{64}$");
    assertThat(
            perform(get("/internal/v1/dossier-render-inputs/" + UUID.randomUUID()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  // ---------------------------------------------------------------- DV-11-02, DV-11-03

  @Test
  void congNoiBoBieuMauNhapVaXemTruoc() throws Exception {
    UUID unit = UUID.randomUUID();
    List<UUID> decided = insertProfiles(unit, 1, "DA_QUYET_DINH");
    List<UUID> open = insertProfiles(unit, 1, "CHO_QUYET_DINH");

    MvcResult drafts =
        perform(
            post("/internal/v1/dossier-draft-forms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "formCode",
                            "MAU_3",
                            "periodId",
                            year,
                            "profileIds",
                            List.of(decided.get(0), open.get(0))))));
    assertThat(drafts.getResponse().getStatus()).isEqualTo(200);
    JsonNode forms = body(drafts).get("forms");
    assertThat(forms).hasSize(1);
    assertThat(forms.get(0).get("templateVersion").asInt()).isEqualTo(2);
    assertThat(forms.get(0).get("reopenRequestId").isNull()).isTrue();
    UUID form = UUID.fromString(forms.get(0).get("formInstanceId").asText());
    Boolean draftRow =
        inDossier(
            jdbc ->
                jdbc.sql(
                        "SELECT dossier_package_id IS NULL AND cardinality(source_profile_ids) = 2"
                            + " FROM dossier.form_instance WHERE id = ?")
                    .param(form)
                    .query(Boolean.class)
                    .single());
    assertThat(draftRow).isTrue();
    MvcResult data = perform(get("/internal/v1/dossier-form-instances/" + form + "/render-data"));
    assertThat(data.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            java.util.HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(data.getResponse().getContentAsByteArray())))
        .isEqualTo(forms.get(0).get("dataSnapshotHash").asText());
    assertThat(json.readTree(data.getResponse().getContentAsByteArray()).get("danhSachHoSo"))
        .hasSize(2);

    MvcResult invalid =
        perform(
            post("/internal/v1/dossier-draft-forms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("formCode", "MAU_9", "periodId", year, "profileIds", decided))));
    assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
    MvcResult missing =
        perform(
            post("/internal/v1/dossier-draft-forms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "formCode",
                            "MAU_3",
                            "periodId",
                            year,
                            "profileIds",
                            List.of(UUID.randomUUID())))));
    assertThat(missing.getResponse().getStatus()).isEqualTo(404);

    MvcResult preview =
        perform(
            post("/internal/v1/dossier-form-previews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "formCode",
                            "BANG_CHAM_DIEM",
                            "sourceModule",
                            "appraisal",
                            "sourceRefId",
                            open.get(0)))));
    assertThat(preview.getResponse().getStatus()).isEqualTo(200);
    JsonNode p = body(preview);
    assertThat(p.get("policyPackCode").asText()).isEqualTo(PACK_CODE);
    assertThat(json.readTree(p.get("data").asText()).at("/hoSo/hoSoId").asText())
        .isEqualTo(open.get(0).toString());
    MvcResult other =
        perform(
            post("/internal/v1/dossier-form-previews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "formCode",
                            "MAU_3",
                            "sourceModule",
                            "oversight",
                            "sourceRefId",
                            open.get(0)))));
    assertThat(other.getResponse().getStatus()).isEqualTo(404);
  }

  // ---------------------------------------------------------------- đo EVT-17

  @Test
  void doXuLyEvt17TheoLo() throws Exception {
    int n = Integer.getInteger("dossier.perf.events", 5_000);
    UUID unit = UUID.randomUUID();
    List<UUID> ids = insertProfiles(unit, 1, "DA_QUYET_DINH");
    deliverDecided(ids.get(0), UUID.randomUUID());
    UUID pkg = (UUID) packagesOf(unit).get(0).get("id");
    UUID profile = ids.get(0);
    inDossier(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO dossier.form_instance (id, created_by, updated_by, period_id,"
                        + " period_bucket, dossier_package_id, profile_id, form_code,"
                        + " template_version, data_snapshot_hash, status, render_job_id)"
                        + " SELECT gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'MAU_1C', '', "
                        + " sha256(g::text::bytea), 'CHO_SINH', gen_random_uuid()"
                        + " FROM generate_series(1, ?) g")
                .params(OFFICER, OFFICER, year, BUCKET, pkg, profile, n)
                .update());
    List<UUID> forms = formIds(pkg);
    List<ConsumerRecord<String, byte[]>> records = new ArrayList<>(forms.size());
    for (UUID form : forms) {
      records.add(generatedRecord(form, UUID.randomUUID(), sha(form.toString())));
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    long start = System.nanoTime();
    List<Future<InboxOutcome>> results = new ArrayList<>();
    for (ConsumerRecord<String, byte[]> r : records) {
      results.add(
          pool.submit(
              () -> consumer.processor().process(consumer.generatedEndpoint(), r, () -> {})));
    }
    for (Future<InboxOutcome> f : results) {
      assertThat(f.get(30, TimeUnit.MINUTES)).isEqualTo(InboxOutcome.PROCESSED);
    }
    pool.shutdown();
    long consumed = System.nanoTime();
    service.settle();
    long settled = System.nanoTime();
    assertThat(packagesOf(unit).get(0).get("status")).isEqualTo("CHO_KY");
    double seconds = (consumed - start) / 1e9;
    System.out.printf(
        "CORE-06-06 đo EVT-17: %d sự kiện, 8 luồng, %.1f s (%.0f sự kiện/s), tính lại gói %.0f ms%n",
        forms.size(), seconds, forms.size() / seconds, (settled - consumed) / 1e6);
  }

  // ---------------------------------------------------------------- tiện ích

  private List<UUID> insertProfiles(UUID unit, int n, String status) throws Exception {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add(UUID.randomUUID());
    }
    inAppraisal(
        jdbc -> {
          for (int i = 0; i < ids.size(); i++) {
            jdbc.sql(
                    "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by,"
                        + " period_id, period_bucket, person_id, flow, org_unit_id, assignment_id,"
                        + " job_title_id, is_head, policy_pack_version_id, status, final_rating_code,"
                        + " decided_at) VALUES (?, ?, ?, ?, ?, ?, 'DANG', ?, ?, ?, ?, ?, ?, ?, ?)")
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
                    status,
                    "DA_QUYET_DINH".equals(status) ? "HTTNV" : null,
                    "DA_QUYET_DINH".equals(status) ? java.sql.Timestamp.from(Instant.now()) : null)
                .update();
          }
          return null;
        });
    return ids;
  }

  private void decide(UUID profile) throws Exception {
    inAppraisal(
        jdbc ->
            jdbc.sql(
                    "UPDATE appraisal.appraisal_profile SET status = 'DA_QUYET_DINH',"
                        + " final_rating_code = 'HTTNV', decided_at = now() WHERE id = ?")
                .param(profile)
                .update());
  }

  private UUID personOf(UUID profile) throws Exception {
    return inAppraisal(
        jdbc ->
            jdbc.sql("SELECT person_id FROM appraisal.appraisal_profile WHERE id = ?")
                .param(profile)
                .query(UUID.class)
                .single());
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

  private List<Map<String, Object>> packagesOf(UUID unit) throws Exception {
    return inDossier(
        jdbc ->
            jdbc.sql(
                    "SELECT id, code, status, row_version FROM dossier.dossier_package"
                        + " WHERE org_unit_id = ?")
                .param(unit)
                .query()
                .listOfRows());
  }

  private long count(UUID pkg, String code) throws Exception {
    return code == null
        ? jdbcDossier(
            "SELECT count(*) FROM dossier.form_instance WHERE dossier_package_id = ?", pkg)
        : inDossier(
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM dossier.form_instance WHERE dossier_package_id = ?"
                            + " AND form_code = ?")
                    .params(pkg, code)
                    .query(Long.class)
                    .single());
  }

  private long jdbcDossier(String sql, UUID arg) throws Exception {
    return inDossier(jdbc -> jdbc.sql(sql).param(arg).query(Long.class).single());
  }

  private List<UUID> formIds(UUID pkg) throws Exception {
    return inDossier(
        jdbc ->
            jdbc.sql(
                    "SELECT id FROM dossier.form_instance WHERE dossier_package_id = ?"
                        + " ORDER BY form_code, id")
                .param(pkg)
                .query(UUID.class)
                .list());
  }

  private UUID requestOf(UUID form) throws Exception {
    return inDossier(
        jdbc ->
            jdbc.sql("SELECT render_job_id FROM dossier.form_instance WHERE id = ?")
                .param(form)
                .query(UUID.class)
                .single());
  }

  private byte[] pdfSha(UUID form) throws Exception {
    return inDossier(
        jdbc ->
            jdbc.sql("SELECT pdf_sha256 FROM dossier.form_instance WHERE id = ?")
                .param(form)
                .query(byte[].class)
                .single());
  }

  private byte[] snapshotHash(UUID form) throws Exception {
    return inDossier(
        jdbc ->
            jdbc.sql("SELECT data_snapshot_hash FROM dossier.form_instance WHERE id = ?")
                .param(form)
                .query(byte[].class)
                .single());
  }

  private static byte[] sha(String s) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
  }

  private MvcResult createPackage(UUID unit, String kind, String key) throws Exception {
    return perform(
        post("/api/v1/dossiers/packages")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"periodId\":\""
                    + year
                    + "\",\"orgUnitId\":\""
                    + unit
                    + "\",\"packageKind\":\""
                    + kind
                    + "\",\"deadline\":\"2027-02-01\"}"));
  }

  private MvcResult regenerate(UUID pkg, String ifMatch) throws Exception {
    return perform(
        post("/api/v1/dossiers/packages/" + pkg + "/regenerate")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  private InboxOutcome deliverDecided(UUID profile, UUID eventId) {
    XepLoaiDaQuyetDinh payload =
        XepLoaiDaQuyetDinh.newBuilder()
            .setProfileId(profile)
            .setPersonId(UUID.randomUUID())
            .setFlow("DANG")
            .setRatingCode("HTTNV")
            .setScoreSnapshotId(UUID.randomUUID())
            .setDeciderId(OFFICER)
            .setAggregateVersion(1)
            .build();
    InboxOutcome outcome =
        deliver(
            consumer.decidedEndpoint(),
            EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH,
            "appraisal.rating.decided.v1",
            eventId,
            payload);
    // Tác vụ định kỳ tắt trong kiểm thử: tự gửi lệnh sinh đã xếp hàng sau giao dịch inbox.
    service.settle();
    return outcome;
  }

  private InboxOutcome deliverGenerated(UUID form, UUID eventId, byte[] sha) {
    return consumer
        .processor()
        .process(consumer.generatedEndpoint(), generatedRecord(form, eventId, sha), () -> {});
  }

  private ConsumerRecord<String, byte[]> generatedRecord(UUID form, UUID eventId, byte[] sha) {
    HoSoSoDaSinh payload =
        HoSoSoDaSinh.newBuilder()
            .setRenderJobId(UUID.randomUUID())
            .setFormInstanceId(form)
            .setFormCode("BANG_CHAM_DIEM")
            .setObjectKey("dossier/forms/" + form + "/" + HexFormat.of().formatHex(sha) + ".pdf")
            .setPdfSha256(ByteBuffer.wrap(sha))
            .setAggregateVersion(1)
            .build();
    return record(
        EventTopics.EVT_17_HO_SO_SO_DA_SINH, "dossier.form.generated.v1", eventId, payload);
  }

  private InboxOutcome deliver(
      InboxEndpoint endpoint, String topic, String type, UUID eventId, SpecificRecord payload) {
    return consumer.processor().process(endpoint, record(topic, type, eventId, payload), () -> {});
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
