package vn.viettel.okrkpi.workflow.infrastructure.periodclose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseControlService;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseRequest;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStatus;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepStatus;
import vn.viettel.okrkpi.workflow.infrastructure.saga.FlowableSagaEngine;
import vn.viettel.okrkpi.workflow.infrastructure.saga.SagaWorker;

/**
 * Saga {@code CHOT_KY} (DV-06-06) trên PostgreSQL và engine Flowable thật với các bên tham gia giả
 * theo hợp đồng tác vụ dài: đường chính đủ sáu bước, tiền điều kiện không đạt, ký số lỗi rồi tiếp
 * tục sau "4 giờ" giả lập, worker thay thế giữa bước tính điểm, gọi trùng khoá.
 */
@SpringBootTest(
    properties = {
      "flowable.async-executor-activate=true",
      "flowable.process.async.executor.default-async-job-acquire-wait-time=100ms",
      "flowable.process.async.executor.reset-expired-jobs-interval=200ms",
      "okrkpi.saga.worker.enabled=false",
      "okrkpi.saga.period-close.signing-backoff=20ms",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(PeriodCloseSagaIT.Participants.class)
class PeriodCloseSagaIT {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a006");
  private static final UUID CLIENT = UUID.fromString("0192f1a2-0000-7000-8000-00000000c006");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_wf_period_close")
          .withUsername("postgres")
          .withPassword("postgres");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("flowable.database-schema-update", () -> "false");
    registry.add("flowable.check-process-definitions", () -> "false");
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private SagaService sagas;
  @Autowired private FlowableSagaEngine engine;
  @Autowired private JdbcClient jdbc;
  @Autowired private FakeParticipants fake;
  @Autowired private PeriodCloseControlService control;

  private SagaWorker worker;

  @BeforeEach
  void setUp() {
    worker = new SagaWorker(engine, sagas, 16, Duration.ofSeconds(1));
  }

  /** Bên tham gia giả: mỗi tác vụ chạy một lượt thăm dò rồi xong, theo kịch bản của kỳ. */
  static class Participants {

    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    FakeParticipants periodCloseGateway() {
      return new FakeParticipants();
    }
  }

  static final class FakeParticipants implements PeriodCloseGateway {

    /** Lệnh đã giao theo kỳ: "LỆNH:jobId". */
    final Map<UUID, List<String>> submitted = new ConcurrentHashMap<>();

    final Map<UUID, List<String>> undone = new ConcurrentHashMap<>();
    final Map<UUID, AtomicInteger> polls = new ConcurrentHashMap<>();

    /** Kỳ có tiền điều kiện không đạt. */
    final Set<UUID> notReady = ConcurrentHashMap.newKeySet();

    /** Số lần ký số đầu tiên thất bại theo kỳ. */
    final Map<UUID, Integer> signingFailures = new ConcurrentHashMap<>();

    /** Kỳ mà tác vụ của một lệnh còn chạy tới khi được thả. */
    final Map<UUID, Command> held = new ConcurrentHashMap<>();

    /** Số hồ sơ lỗi của lần tính chốt kỳ theo kỳ. */
    final Map<UUID, Integer> scoringFailures = new ConcurrentHashMap<>();

    /** Số hồ sơ mà các lô chạy lại đã tính, theo kỳ. */
    final Map<UUID, AtomicInteger> retriedProfiles = new ConcurrentHashMap<>();

    /** Kỳ có một kết quả bị sửa sau khi lưu (kiểm tất định phải phát hiện). */
    final Set<UUID> tampered = ConcurrentHashMap.newKeySet();

    @Override
    public PreconditionReport checkPreconditions(PeriodCloseRequest request) {
      if (notReady.contains(request.periodId())) {
        return new PreconditionReport(
            List.of("còn 3 đề nghị gia hạn chờ duyệt", "cây tổ chức còn 1 điểm lệch mức Cao"),
            List.of("12 minh chứng đang chờ nghiệm thu sẽ không được tính trong kỳ"));
      }
      return new PreconditionReport(
          List.of(), List.of("2 minh chứng đang chờ nghiệm thu sẽ không được tính trong kỳ"));
    }

    @Override
    public void submit(Command command, PeriodCloseRequest request, UUID jobId) {
      List<String> list =
          submitted.computeIfAbsent(request.periodId(), k -> new CopyOnWriteArrayList<>());
      String entry = command + ":" + jobId;
      if (!list.contains(entry)) {
        list.add(entry);
      }
    }

    @Override
    public JobState state(Command command, PeriodCloseRequest request, UUID jobId) {
      List<String> list = submitted.getOrDefault(request.periodId(), List.of());
      if (!list.contains(command + ":" + jobId)) {
        return JobState.missing();
      }
      int n = polls.computeIfAbsent(jobId, k -> new AtomicInteger()).incrementAndGet();
      if (held.get(request.periodId()) == command) {
        return JobState.running(40);
      }
      if (n == 1) {
        return JobState.running(50);
      }
      if (command == Command.TINH_DIEM) {
        return JobState.done(scoringFailures.getOrDefault(request.periodId(), 0));
      }
      if (command == Command.TINH_LAI_HO_SO_LOI) {
        if (n == 2) {
          // Lô mới chỉ gồm hồ sơ trong hàng đợi lỗi.
          retriedProfiles
              .computeIfAbsent(request.periodId(), k -> new AtomicInteger())
              .addAndGet(scoringFailures.getOrDefault(request.periodId(), 0));
          scoringFailures.put(request.periodId(), 0);
        }
        return JobState.done(0);
      }
      if (command == Command.KIEM_TAT_DINH) {
        return JobState.done(tampered.contains(request.periodId()) ? 1 : 0);
      }
      if (command == Command.KY_SO_LO) {
        long signAttempts = list.stream().filter(s -> s.startsWith("KY_SO_LO:")).count();
        if (signAttempts <= signingFailures.getOrDefault(request.periodId(), 0)) {
          return JobState.failed("Mất kết nối HSM khi ký lô");
        }
      }
      return JobState.done();
    }

    @Override
    public void undo(Command command, PeriodCloseRequest request, String undoKey) {
      undone
          .computeIfAbsent(request.periodId(), k -> new CopyOnWriteArrayList<>())
          .add(command.name());
    }

    List<String> commands(UUID periodId) {
      return submitted.getOrDefault(periodId, List.of()).stream()
          .map(s -> s.substring(0, s.indexOf(':')))
          .toList();
    }
  }

  @Test
  void duongChinhDuSauBuocVaTheoDoiDuocNhuTacVu() throws Exception {
    UUID period = UUID.randomUUID();
    String location = start(period, UUID.randomUUID());
    UUID id = UUID.fromString(location.substring("/api/v1/jobs/".length()));

    long t0 = System.nanoTime();
    SagaInstance done = drive(id);
    System.out.println(
        "Saga chốt kỳ (dịch vụ giả) chạy hết sáu bước trong "
            + Duration.ofNanos(System.nanoTime() - t0).toMillis()
            + " ms");

    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(done.pointOfNoReturnPassed()).isTrue();
    assertThat(done.steps()).allMatch(s -> s.status() == SagaStepStatus.XONG);
    assertThat(done.steps())
        .extracting(s -> s.stepCode())
        .containsExactly(
            "KIEM_TIEN_DIEU_KIEN",
            "DAT_DANG_KHOA",
            "TINH_DIEM_CHOT_KY",
            "SINH_BIEU_MAU",
            "KY_SO_LO",
            "HOAN_TAT");
    assertThat(fake.commands(period))
        .containsExactly(
            "KHOA_KY",
            "TINH_DIEM",
            "KIEM_TAT_DINH",
            "SINH_BIEU_MAU",
            "KY_SO_LO",
            "HOAN_TAT_KY",
            "NIEM_PHONG");
    assertThat(fake.undone).doesNotContainKey(period);

    mvc.perform(asClient(get(location)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HOAN_TAT"))
        .andExpect(jsonPath("$.type").value("PERIOD_LOCK"))
        .andExpect(jsonPath("$.progress").value(100));

    // BR-08-10: mọi bước ghi sổ cái qua sys.audit.v1.
    Integer ledger =
        jdbc.sql(
                "SELECT count(*) FROM flowable.outbox_event WHERE topic = 'sys.audit.v1'"
                    + " AND aggregate_id = ?")
            .param(id)
            .query(Integer.class)
            .single();
    assertThat(ledger).isGreaterThanOrEqualTo(13);
  }

  @Test
  void tienDieuKienKhongDatThiTacVuThatBaiVoiProblemVaKhongKhoaKy() throws Exception {
    UUID period = UUID.randomUUID();
    fake.notReady.add(period);
    String location = start(period, UUID.randomUUID());
    UUID id = UUID.fromString(location.substring("/api/v1/jobs/".length()));

    SagaInstance undone = drive(id);

    assertThat(undone.status()).isEqualTo(SagaStatus.DA_BU_TRU);
    assertThat(undone.step(1).status()).isEqualTo(SagaStepStatus.LOI);
    assertThat(undone.step(1).attempts()).isEqualTo(1);
    assertThat(fake.commands(period)).isEmpty();
    mvc.perform(asClient(get(location)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("THAT_BAI"))
        .andExpect(jsonPath("$.problem.code").value("PERIOD-422-PRECONDITION"))
        .andExpect(jsonPath("$.problem.msg").value("MSG-08-02"))
        .andExpect(jsonPath("$.problem.status").value(422))
        .andExpect(
            jsonPath("$.problem.detail")
                .value(
                    "Kỳ chưa đủ điều kiện để khoá: còn 3 đề nghị gia hạn chờ duyệt; cây tổ chức"
                        + " còn 1 điểm lệch mức Cao."));
  }

  @Test
  void kySoLoiNhieuLanThiLuiDanRoiTiepTucKhongTinhLaiBuocTruoc() throws Exception {
    UUID period = UUID.randomUUID();
    // 12 lần đầu lỗi, lần thứ 13 (cuối cùng của chính sách khoảng 4 giờ) thành công.
    fake.signingFailures.put(period, 12);
    UUID id = idOf(start(period, UUID.randomUUID()));

    SagaInstance done = drive(id);

    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(done.step(5).attempts()).isEqualTo(13);
    List<String> commands = fake.commands(period);
    assertThat(commands.stream().filter("KY_SO_LO"::equals).count()).isEqualTo(13);
    assertThat(commands.stream().filter("TINH_DIEM"::equals).count()).isEqualTo(1);
    assertThat(commands.stream().filter("SINH_BIEU_MAU"::equals).count()).isEqualTo(1);
    assertThat(commands.stream().filter("KHOA_KY"::equals).count()).isEqualTo(1);
    assertThat(fake.undone).doesNotContainKey(period);
  }

  @Test
  void kySoHetLanThuThiChoNguoiVanHanhKhongTuBuTru() throws Exception {
    UUID period = UUID.randomUUID();
    fake.signingFailures.put(period, 100);
    UUID id = idOf(start(period, UUID.randomUUID()));

    SagaInstance stuck = driveUntil(id, s -> s.status() == SagaStatus.LOI_CAN_XU_LY);

    assertThat(stuck.currentStepNo()).isEqualTo(5);
    assertThat(stuck.step(5).lastError()).contains("HSM");
    assertThat(fake.undone).doesNotContainKey(period);

    // Hai người duyệt huỷ: hoàn tác từ bước ký (huỷ yêu cầu ký lỗi còn treo) về bước khoá.
    TenantContext.run(
        TenantId.of(TENANT), () -> control.cancel(id, UUID.randomUUID(), UUID.randomUUID()));
    SagaInstance undone = drive(id);
    assertThat(undone.status()).isEqualTo(SagaStatus.DA_BU_TRU);
    assertThat(fake.undone.get(period))
        .containsExactly("KY_SO_LO", "SINH_BIEU_MAU", "TINH_DIEM", "KHOA_KY");
  }

  @Test
  void workerMoiTiepQuanGiuaBuocTinhDiemKhongGiaoLaiTacVu() throws Exception {
    UUID period = UUID.randomUUID();
    fake.held.put(period, PeriodCloseGateway.Command.TINH_DIEM);
    UUID id = idOf(start(period, UUID.randomUUID()));

    SagaInstance scoring =
        driveUntil(
            id,
            s ->
                s.currentStepNo() == 3
                    && s.status() == SagaStatus.CHO_SU_KIEN
                    && fake.polls.values().stream().anyMatch(n -> n.get() > 2));
    assertThat(scoring.step(3).status()).isEqualTo(SagaStepStatus.DANG_CHAY);

    // Pod chết: worker mới (cùng CSDL, cùng engine) tiếp quản; tác vụ tính điểm giữ nguyên.
    worker = new SagaWorker(engine, sagas, 16, Duration.ofSeconds(1));
    fake.held.remove(period);
    SagaInstance done = drive(id);

    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(fake.commands(period).stream().filter("TINH_DIEM"::equals).count()).isEqualTo(1);
    assertThat(done.step(3).attempts()).isEqualTo(1);
  }

  @Test
  void goiTrungKhoaTraLaiTacVuCuLenhKhacCungKyBiTuChoi() throws Exception {
    UUID period = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    String first = start(period, order);
    assertThat(start(period, order)).isEqualTo(first);
    mvc.perform(
            asClient(post("/api/v1/workflow/sagas/period-close"))
                .header("Idempotency-Key", "CK-" + period)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(period, UUID.randomUUID())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORKFLOW-409-SAGA-EXISTS"));
  }

  // --- DV-06-07: bù trừ, chạy lại hồ sơ lỗi, kiểm tất định ----------------------------------

  @Test
  void huyOTungBuocTruocDiemKhongQuayLaiDeuVeMoVaPhatHuyKhoaMotLan() throws Exception {
    Map<Integer, PeriodCloseGateway.Command> steps =
        Map.of(
            2, PeriodCloseGateway.Command.KHOA_KY,
            3, PeriodCloseGateway.Command.TINH_DIEM,
            4, PeriodCloseGateway.Command.SINH_BIEU_MAU,
            5, PeriodCloseGateway.Command.KY_SO_LO);
    for (Map.Entry<Integer, PeriodCloseGateway.Command> at : steps.entrySet()) {
      UUID period = UUID.randomUUID();
      fake.held.put(period, at.getValue());
      UUID id = idOf(start(period, UUID.randomUUID()));
      driveUntil(id, s -> s.currentStepNo() == at.getKey() && s.status() == SagaStatus.CHO_SU_KIEN);

      TenantContext.run(
          TenantId.of(TENANT), () -> control.cancel(id, UUID.randomUUID(), UUID.randomUUID()));
      SagaInstance undone = drive(id);

      assertThat(undone.status()).as("huỷ ở bước %s", at.getKey()).isEqualTo(SagaStatus.DA_BU_TRU);
      List<String> undo = fake.undone.get(period);
      // Thứ tự ngược, gồm cả lệnh đang chờ của bước hiện tại; trả kỳ về MO (EVT-26) đúng một lần.
      assertThat(undo.get(0)).isEqualTo(at.getValue().name());
      assertThat(undo.get(undo.size() - 1)).isEqualTo("KHOA_KY");
      assertThat(undo.stream().filter("KHOA_KY"::equals).count()).isEqualTo(1);
      assertThat(undo).hasSize(at.getKey() - 1);
    }
  }

  @Test
  void huySauDiemKhongQuayLaiBi409VaCungNguoiDuyetBiChan() throws Exception {
    UUID period = UUID.randomUUID();
    fake.held.put(period, PeriodCloseGateway.Command.HOAN_TAT_KY);
    UUID id = idOf(start(period, UUID.randomUUID()));
    SagaInstance last =
        driveUntil(id, s -> s.currentStepNo() == 6 && s.status() == SagaStatus.CHO_SU_KIEN);
    assertThat(last.pointOfNoReturnPassed()).isTrue();

    UUID same = UUID.randomUUID();
    assertThatThrownBy(
            () -> TenantContext.run(TenantId.of(TENANT), () -> control.cancel(id, same, same)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("PERIOD-403-SAME-PERSON"));
    assertThatThrownBy(
            () ->
                TenantContext.run(
                    TenantId.of(TENANT),
                    () -> control.cancel(id, UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("PERIOD-409-POINT-OF-NO-RETURN"));
    fake.held.remove(period);
    assertThat(drive(id).status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(fake.undone).doesNotContainKey(period);
  }

  @Test
  void chayLai100HoSoLoiChiTinhDung100HoSoVaGoiTrungKhongTaoLoMoi() throws Exception {
    UUID period = UUID.randomUUID();
    fake.scoringFailures.put(period, 100);
    UUID id = idOf(start(period, UUID.randomUUID()));
    SagaInstance stuck = driveUntil(id, s -> s.status() == SagaStatus.LOI_CAN_XU_LY);
    assertThat(stuck.currentStepNo()).isEqualTo(3);
    assertThat(stuck.step(3).lastError()).contains("Còn 100 hồ sơ");

    UUID first =
        TenantContext.call(TenantId.of(TENANT), () -> control.retryFailedProfiles(id, "lan-1"));
    UUID again =
        TenantContext.call(TenantId.of(TENANT), () -> control.retryFailedProfiles(id, "lan-1"));
    assertThat(again).isEqualTo(first);
    SagaInstance done = drive(id);

    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(fake.retriedProfiles.get(period).get()).isEqualTo(100);
    List<String> commands = fake.commands(period);
    assertThat(commands.stream().filter("TINH_LAI_HO_SO_LOI"::equals).count()).isEqualTo(1);
    assertThat(commands.stream().filter("TINH_DIEM"::equals).count()).isEqualTo(1);
    assertThat(done.step(3).attempts()).isEqualTo(1);
  }

  @Test
  void chapNhanDeLaiHoSoLoiKemLyDoThiSagaDiTiep() throws Exception {
    UUID period = UUID.randomUUID();
    fake.scoringFailures.put(period, 5);
    UUID id = idOf(start(period, UUID.randomUUID()));
    driveUntil(id, s -> s.status() == SagaStatus.LOI_CAN_XU_LY);
    assertThatThrownBy(
            () ->
                TenantContext.run(
                    TenantId.of(TENANT),
                    () -> control.acceptFailedProfiles(id, UUID.randomUUID(), " ")))
        .isInstanceOf(BusinessException.class);

    TenantContext.run(
        TenantId.of(TENANT),
        () ->
            control.acceptFailedProfiles(
                id, UUID.randomUUID(), "Hồ sơ chuyển công tác, xử lý ở kỳ sau"));
    SagaInstance done = drive(id);
    assertThat(done.status()).isEqualTo(SagaStatus.HOAN_TAT);
    assertThat(done.notes()).containsKey("chapNhanHoSoLoi");
  }

  @Test
  void kiemTatDinhPhatHienKetQuaBiSuaThiDungChoNguoiVanHanh() throws Exception {
    UUID period = UUID.randomUUID();
    fake.tampered.add(period);
    UUID id = idOf(start(period, UUID.randomUUID()));

    SagaInstance stuck = driveUntil(id, s -> s.status() == SagaStatus.LOI_CAN_XU_LY);

    assertThat(stuck.currentStepNo()).isEqualTo(3);
    assertThat(stuck.step(3).lastError()).contains("1 hồ sơ cho kết quả khác");
    assertThat(fake.commands(period)).doesNotContain("SINH_BIEU_MAU");
  }

  // --- Tiện ích ----------------------------------------------------------------------------

  private String start(UUID period, UUID order) throws Exception {
    return mvc.perform(
            asClient(post("/api/v1/workflow/sagas/period-close"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(period, order)))
        .andExpect(status().isAccepted())
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  private static UUID idOf(String location) {
    return UUID.fromString(location.substring("/api/v1/jobs/".length()));
  }

  private String body(UUID period, UUID order) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "periodId",
            period,
            "lockOrderId",
            order,
            "unitIds",
            List.of(UUID.randomUUID()),
            "requestedBy",
            UUID.randomUUID()));
  }

  private MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request) {
    return request.with(
        jwt()
            .jwt(j -> j.subject(CLIENT.toString()).claim("tenant_id", TENANT.toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_service_client")));
  }

  private SagaInstance load(UUID id) throws Exception {
    return TenantContext.call(TenantId.of(TENANT), () -> sagas.find(id).orElseThrow());
  }

  private SagaInstance drive(UUID id) throws Exception {
    return driveUntil(id, s -> s.status().isTerminal());
  }

  private SagaInstance driveUntil(UUID id, java.util.function.Predicate<SagaInstance> until)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      worker.poll();
      worker.reconcile();
      SagaInstance saga = load(id);
      if (until.test(saga)) {
        return saga;
      }
      Thread.sleep(30);
    }
    SagaInstance last = load(id);
    throw new AssertionError(
        "Saga "
            + id
            + " không tới trạng thái mong đợi: "
            + last.status()
            + " bước "
            + last.currentStepNo()
            + " "
            + last.steps().stream()
                .map(
                    s -> s.stepCode() + "=" + s.status() + "/" + s.attempts() + "/" + s.lastError())
                .toList());
  }
}
