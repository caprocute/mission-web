package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.LockProjectionRepository;
import vn.viettel.okrkpi.config.domain.DefaultPeriodCalendar;
import vn.viettel.okrkpi.config.domain.LockBatch;
import vn.viettel.okrkpi.config.domain.LockCancelRequest;
import vn.viettel.okrkpi.config.domain.LockCommand;
import vn.viettel.okrkpi.config.domain.LockEvent;
import vn.viettel.okrkpi.config.domain.LockFailure;
import vn.viettel.okrkpi.config.domain.LockOrderStatus;
import vn.viettel.okrkpi.config.domain.LockSagaDiagram;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * CORE-08-05 phần điều khiển saga chốt kỳ: tạm dừng, tiếp tục, chạy lại hồ sơ lỗi, đề nghị và duyệt
 * huỷ (bốn mắt, điểm không quay lại), bù trừ xong, cùng tiến trình, lô, lỗi, nhật ký, sơ đồ.
 */
class PeriodLockControlServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2027-01-04T00:44:00Z");
  private static final UUID ADMIN = UUID.randomUUID();
  private static final UUID LEADER = UUID.randomUUID();
  private static final UUID STARTER = UUID.randomUUID();
  private static final UUID SECOND = UUID.randomUUID();
  private static final UUID SAGA = UUID.randomUUID();
  private static final String REASON =
      "Phát hiện sai sót cấu hình trọng số khối 3 khi đang tính điểm.";

  private final InMemoryPeriodRepository periods = new InMemoryPeriodRepository();
  private final PeriodLockServiceTest.Orders orders = new PeriodLockServiceTest.Orders();
  private final Projections projections = new Projections();
  private final List<String> commands = new ArrayList<>();
  private final List<String> audits = new ArrayList<>();
  private final List<String> notices = new ArrayList<>();
  private final List<String> reverts = new ArrayList<>();
  private final AtomicInteger jobs = new AtomicInteger();
  private final Clock clock = () -> NOW;

  private final PeriodLockControlService service =
      new PeriodLockControlService(
          orders,
          projections,
          periods,
          (order, command, profiles, comment, by) -> {
            commands.add(command + ":" + profiles + ":" + comment);
            return UUID.nameUUIDFromBytes(("job" + jobs.incrementAndGet()).getBytes());
          },
          new LockOrderNotices() {
            @Override
            public void requested(PeriodLockOrder order, String name, List<UUID> to) {}

            @Override
            public void approved(PeriodLockOrder order, String name, List<UUID> to) {}

            @Override
            public void cancelRequested(PeriodLockOrder order, String name, List<UUID> to) {
              notices.add("cancelRequested:" + name + ":" + to);
            }

            @Override
            public void cancelCompleted(PeriodLockOrder order, String name, List<UUID> to) {
              notices.add("cancelCompleted:" + to);
            }
          },
          (tenant, role, at) ->
              "second_approver".equals(role) ? List.of(SECOND, ADMIN) : List.of(ADMIN),
          (tenant, actor, action, type, objectId, aggregate, version) ->
              audits.add(action + ":" + version),
          (tenant, period, saga, actor, order, reason) -> {
            reverts.add(period.value() + ":" + reason);
            periods
                .findById(tenant, period)
                .orElseThrow()
                .moveTo(PeriodState.MO, UUID.randomUUID(), saga, actor, NOW);
          },
          clock);

  private final PeriodId period = PeriodId.of(UUID.randomUUID());
  private PeriodLockOrder order;

  PeriodLockControlServiceTest() {
    periods.put(period(period, PeriodState.DANG_KHOA));
    order = running(period);
    orders.rows.put(order.id(), order);
  }

  private static Period period(PeriodId id, PeriodState state) {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 12, 31);
    Period p =
        Period.builder()
            .id(id)
            .tenantId(TENANT)
            .code("2026-Q4")
            .name("Quý IV/2026")
            .kind(PeriodKind.QUY)
            .flow(PeriodFlow.CA_HAI)
            .startDate(start)
            .endDate(end)
            .schedule(DefaultPeriodCalendar.scheduleOf(start, end))
            .created(ADMIN, NOW)
            .build();
    if (state == PeriodState.DANG_KHOA) {
      p.moveTo(PeriodState.DANG_KHOA, UUID.randomUUID(), SAGA, ADMIN, NOW);
    }
    return p;
  }

  private static PeriodLockOrder running(PeriodId periodId) {
    PeriodLockOrder o =
        PeriodLockOrder.request(
            UUID.randomUUID(), TENANT, periodId, "Thông báo 21-TB/ĐUK", null, ADMIN, NOW, "CK-1");
    o.approve(LEADER, NOW);
    o.start(STARTER, SAGA, NOW);
    return o;
  }

  private static Actor mfa(UUID id) {
    return new Actor(id, "aal2", NOW.getEpochSecond() - 60);
  }

  private static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  private void signed() {
    orders.steps.put(
        order.id(), List.of(new LockStep(4, "Ký số lô", "DAT", NOW, NOW, Map.of(), null, null)));
  }

  @Test
  void pauseAndResumeSetFlagSendCommandsAndAreIdempotent() {
    assertThatThrownBy(
            () -> service.pause(TENANT, new Actor(STARTER, "aal1", null), period, 2, null))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(() -> service.pause(TENANT, mfa(STARTER), period, 1, null))
        .isInstanceOf(VersionConflictException.class);

    PeriodLockControlService.Overview paused =
        service.pause(TENANT, mfa(STARTER), period, 2, " Kiểm tra lại trọng số ");
    assertThat(paused.order().pauseRequested()).isTrue();
    assertThat(paused.order().pauseRequestedBy()).isEqualTo(STARTER);
    assertThat(paused.order().pauseRequestedAt()).isEqualTo(NOW);
    assertThat(paused.order().rowVersion()).isEqualTo(3);
    assertThat(paused.sagaStatus()).isEqualTo("CHO_SU_KIEN");
    assertThat(paused.warnings()).anyMatch(w -> w.contains("tạm dừng"));
    assertThat(commands).containsExactly("TAM_DUNG:[]:Kiểm tra lại trọng số");
    assertThat(projections.events).hasSize(1);
    assertThat(projections.events.get(0).event())
        .isEqualTo("Tạm dừng sau bước hiện tại. Kiểm tra lại trọng số");
    assertThat(audits).containsExactly("TAM_DUNG_KHOA_KY:3");

    // Đặt lại khi đã tạm dừng: không đổi gì.
    assertThat(service.pause(TENANT, mfa(STARTER), period, 3, null).order().rowVersion())
        .isEqualTo(3);
    assertThat(commands).hasSize(1);

    PeriodLockControlService.Overview resumed =
        service.resume(TENANT, mfa(STARTER), period, 3, null);
    assertThat(resumed.order().pauseRequested()).isFalse();
    assertThat(resumed.order().rowVersion()).isEqualTo(4);
    assertThat(commands).last().isEqualTo("TIEP_TUC:[]:null");
    assertThat(projections.events.get(1).event()).isEqualTo("Tiếp tục tiến trình khoá kỳ.");
    assertThat(service.resume(TENANT, mfa(STARTER), period, 4, null).order().rowVersion())
        .isEqualTo(4);
    assertThat(commands).hasSize(2);

    orders.failUpdate = true;
    assertThatThrownBy(() -> service.pause(TENANT, mfa(STARTER), period, 4, null))
        .isInstanceOf(VersionConflictException.class);
    orders.failUpdate = false;
    // Bộ nhớ giữ đối tượng đã đổi (giao dịch thật sẽ cuộn lại): đang tạm dừng, phiên bản 5.
    assertThatThrownBy(() -> service.resume(TENANT, mfa(STARTER), period, 5, "x".repeat(1001)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void controlRequiresARunningSaga() {
    PeriodId fresh = PeriodId.of(UUID.randomUUID());
    periods.put(period(fresh, PeriodState.MO));
    assertThatThrownBy(() -> service.pause(TENANT, mfa(STARTER), fresh, 0, null))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(service.overview(TENANT, fresh)).isEmpty();
    assertThat(service.diagram(TENANT, fresh)).isEmpty();
    assertThatThrownBy(() -> service.batches(TENANT, fresh, null, null, 21))
        .isInstanceOf(NoSuchElementException.class);

    PeriodLockOrder done = finished(fresh, LockOrderStatus.HOAN_TAT);
    orders.rows.put(done.id(), done);
    assertThatThrownBy(() -> service.pause(TENANT, mfa(STARTER), fresh, 5, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-SAGA-NOT-RUNNING"));
    assertThatThrownBy(() -> service.resume(TENANT, mfa(STARTER), fresh, 5, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-SAGA-NOT-RUNNING"));
    assertThatThrownBy(() -> service.retryFailed(TENANT, mfa(STARTER), fresh, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-SAGA-NOT-RUNNING"));
    assertThatThrownBy(() -> service.requestCancel(TENANT, mfa(STARTER), fresh, 5, REASON))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-POINT-OF-NO-RETURN"));
    PeriodLockControlService.Overview o = service.overview(TENANT, fresh).orElseThrow();
    assertThat(o.sagaStatus()).isEqualTo("HOAN_TAT");
    assertThat(o.cancellable()).isFalse();
  }

  private static PeriodLockOrder finished(PeriodId periodId, LockOrderStatus status) {
    return new PeriodLockOrder(
        UUID.randomUUID(),
        TENANT,
        periodId,
        status,
        "x",
        null,
        ADMIN,
        NOW,
        LEADER,
        NOW,
        STARTER,
        NOW,
        UUID.randomUUID(),
        "CK-9",
        5);
  }

  @Test
  void retryFailedQueuesTheFailureQueueOrTheNamedProfiles() {
    assertThatThrownBy(() -> service.retryFailed(TENANT, mfa(STARTER), period, List.of()))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-NO-FAILURE"));
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    projections.upsertFailure(TENANT, order.id(), failure(p1));
    projections.upsertFailure(TENANT, order.id(), failure(p2));
    assertThatThrownBy(
            () -> service.retryFailed(TENANT, new Actor(STARTER, "aal2", null), period, null))
        .isInstanceOf(StepUpRequiredException.class);

    PeriodLockControlService.Accepted all = service.retryFailed(TENANT, mfa(STARTER), period, null);
    assertThat(all.jobType()).isEqualTo("PERIOD_LOCK_RETRY");
    assertThat(all.jobId()).isNotNull();
    assertThat(all.at()).isEqualTo(NOW);
    assertThat(commands).containsExactly("CHAY_LAI_LOI:[]:Số hồ sơ chạy lại: 2.");
    assertThat(audits).containsExactly("CHAY_LAI_HO_SO_LOI_KHOA_KY:2");

    service.retryFailed(TENANT, mfa(STARTER), period, List.of(p2, p2, UUID.randomUUID()));
    assertThat(commands).last().isEqualTo("CHAY_LAI_LOI:[" + p2 + "]:Số hồ sơ chạy lại: 1.");
    assertThatThrownBy(
            () -> service.retryFailed(TENANT, mfa(STARTER), period, List.of(UUID.randomUUID())))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-NO-FAILURE"));
    List<UUID> tooMany = new ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      tooMany.add(UUID.randomUUID());
    }
    assertThatThrownBy(() -> service.retryFailed(TENANT, mfa(STARTER), period, tooMany))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));

    assertThat(service.failures(TENANT, period, null, 21)).hasSize(2);
    assertThat(projections.resolveFailures(TENANT, order.id(), List.of(p1), NOW)).isEqualTo(1);
    assertThat(projections.resolveFailures(TENANT, order.id(), List.of(), NOW)).isZero();
    assertThat(service.failures(TENANT, period, null, 21))
        .extracting(LockFailure::profileId)
        .containsExactly(p2);
  }

  private static LockFailure failure(UUID profile) {
    return new LockFailure(
        profile,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Thiếu phân công",
        3,
        NOW);
  }

  @Test
  void cancelNeedsReasonStepUpAndSecondApproverOtherThanRequester() {
    assertThatThrownBy(() -> service.requestCancel(TENANT, mfa(STARTER), period, 2, "ngắn"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.requestCancel(TENANT, mfa(STARTER), period, 2, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.requestCancel(TENANT, new Actor(STARTER, null, null), period, 2, REASON))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(() -> service.approveCancel(TENANT, mfa(SECOND), period, 2, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("CONFIG-409-NOT-PENDING"));

    PeriodLockControlService.Overview requested =
        service.requestCancel(TENANT, mfa(ADMIN), period, 2, "  " + REASON + "  ");
    LockCancelRequest cancel = requested.order().cancelRequest();
    assertThat(cancel.requestedBy()).isEqualTo(ADMIN);
    assertThat(cancel.reason()).isEqualTo(REASON);
    assertThat(cancel.approved()).isFalse();
    assertThat(requested.cancellable()).isTrue();
    assertThat(requested.warnings()).anyMatch(w -> w.contains("người duyệt thứ hai"));
    assertThat(notices).containsExactly("cancelRequested:Quý IV/2026:[" + SECOND + "]");
    assertThat(audits).containsExactly("DE_NGHI_HUY_KHOA_KY:3");
    assertThat(commands).isEmpty();
    // Đề nghị đang chờ: gửi lại không tạo thêm.
    assertThat(service.requestCancel(TENANT, mfa(ADMIN), period, 3, REASON).order().rowVersion())
        .isEqualTo(3);
    assertThat(notices).hasSize(1);

    assertThatThrownBy(() -> service.approveCancel(TENANT, mfa(ADMIN), period, 3, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-403-SAME-PERSON"));
    assertThatThrownBy(
            () -> service.approveCancel(TENANT, new Actor(SECOND, "aal2", null), period, 3, null))
        .isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(() -> service.approveCancel(TENANT, mfa(SECOND), period, 2, null))
        .isInstanceOf(VersionConflictException.class);

    PeriodLockControlService.Accepted approved =
        service.approveCancel(TENANT, mfa(SECOND), period, 3, "Đồng ý huỷ");
    assertThat(approved.jobType()).isEqualTo("PERIOD_UNLOCK");
    assertThat(approved.order().cancelRequest().approvedBy()).isEqualTo(SECOND);
    assertThat(approved.order().cancelRequest().approvedAt()).isEqualTo(NOW);
    assertThat(commands).containsExactly("BU_TRU:[]:Đồng ý huỷ");
    assertThat(audits).last().isEqualTo("DUYET_HUY_KHOA_KY:4");
    PeriodLockControlService.Overview compensating = service.overview(TENANT, period).orElseThrow();
    assertThat(compensating.sagaStatus()).isEqualTo("DANG_BU_TRU");
    assertThat(compensating.cancellable()).isFalse();
    assertThat(compensating.warnings()).anyMatch(w -> w.contains("Đang huỷ và bù trừ"));

    // Đã duyệt huỷ: không duyệt lại, không điều khiển nữa.
    assertThatThrownBy(() -> service.approveCancel(TENANT, mfa(SECOND), period, 4, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("CONFIG-409-NOT-PENDING"));
    assertThatThrownBy(() -> service.pause(TENANT, mfa(STARTER), period, 4, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-SAGA-NOT-RUNNING"));
    assertThatThrownBy(() -> service.requestCancel(TENANT, mfa(STARTER), period, 4, REASON))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-SAGA-NOT-RUNNING"));

    // Saga bù trừ xong: lệnh DA_HUY, kỳ về MO (EVT-26), tác vụ hoàn tất, thông báo MSG-08-08.
    projections.commands.add(new String[] {order.id().toString(), "BU_TRU", "CHO"});
    assertThat(service.completeCancel(TENANT, order.id(), SECOND)).isTrue();
    assertThat(orders.rows.get(order.id()).status()).isEqualTo(LockOrderStatus.DA_HUY);
    assertThat(reverts).containsExactly(period.value() + ":" + REASON);
    assertThat(periods.findById(TENANT, period).orElseThrow().state()).isEqualTo(PeriodState.MO);
    assertThat(projections.commands.get(0)[2]).isEqualTo("HOAN_TAT");
    assertThat(projections.events).last().satisfies(e -> assertThat(e.evt()).isEqualTo("EVT-26"));
    assertThat(notices).last().isEqualTo("cancelCompleted:[" + ADMIN + "]");
    assertThat(service.completeCancel(TENANT, order.id(), SECOND)).isFalse();
    assertThat(reverts).hasSize(1);
    assertThatThrownBy(() -> service.completeCancel(TENANT, UUID.randomUUID(), SECOND))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void cancelIsBlockedOnceBatchSigningFinished() {
    PeriodLockControlService.Overview before = service.overview(TENANT, period).orElseThrow();
    assertThat(before.cancellable()).isTrue();
    service.requestCancel(TENANT, mfa(ADMIN), period, 2, REASON);
    signed();
    assertThat(service.overview(TENANT, period).orElseThrow().cancellable()).isFalse();
    assertThatThrownBy(() -> service.approveCancel(TENANT, mfa(SECOND), period, 3, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-POINT-OF-NO-RETURN"));
    orders.rows.clear();
    order = running(period);
    orders.rows.put(order.id(), order);
    signed();
    assertThatThrownBy(() -> service.requestCancel(TENANT, mfa(ADMIN), period, 2, REASON))
        .satisfies(e -> assertThat(code(e)).isEqualTo("PERIOD-409-POINT-OF-NO-RETURN"));
  }

  @Test
  void completeCancelBeforeLockStepLeavesPeriodOpen() {
    PeriodId open = PeriodId.of(UUID.randomUUID());
    periods.put(period(open, PeriodState.MO));
    PeriodLockOrder o = running(open);
    o.requestCancel(ADMIN, REASON, NOW);
    o.approveCancel(SECOND, NOW);
    orders.rows.put(o.id(), o);
    assertThat(service.completeCancel(TENANT, o.id(), SECOND)).isTrue();
    assertThat(reverts).isEmpty();
    assertThat(projections.events).last().satisfies(e -> assertThat(e.evt()).isNull());

    PeriodLockOrder pending = running(PeriodId.of(UUID.randomUUID()));
    orders.rows.put(pending.id(), pending);
    assertThatThrownBy(() -> service.completeCancel(TENANT, pending.id(), SECOND))
        .isInstanceOf(PeriodLockOrder.IllegalStatusException.class);
    PeriodLockOrder approvedCancel = running(open);
    approvedCancel.requestCancel(ADMIN, REASON, NOW);
    approvedCancel.approveCancel(SECOND, NOW);
    orders.rows.put(approvedCancel.id(), approvedCancel);
    orders.failUpdate = true;
    assertThatThrownBy(() -> service.completeCancel(TENANT, approvedCancel.id(), SECOND))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void overviewCombinesStepsBatchesFailuresAndWarnings() {
    orders.steps.put(
        order.id(),
        List.of(
            new LockStep(
                0,
                "Kiểm tra tiền điều kiện",
                "DAT_CO_CANH_BAO",
                NOW,
                NOW,
                Map.of(
                    "excludedEvidence",
                    214,
                    "warnings",
                    List.of("Cây tổ chức có lệch mức Thấp.", 7)),
                null,
                null),
            new LockStep(1, "Khoá kỳ", "DAT", NOW, NOW, Map.of("event", "EVT-09"), null, null),
            new LockStep(
                2,
                "Tính điểm hàng loạt",
                "DANG_CHAY",
                NOW,
                null,
                Map.of("profiles", 1000, "scored", 400, "failed", 3),
                null,
                null)));
    PeriodLockControlService.Overview fromSummary = service.overview(TENANT, period).orElseThrow();
    assertThat(fromSummary.totals()).isEqualTo(new PeriodLockControlService.Totals(1000, 400, 3));
    assertThat(fromSummary.excludedEvidence()).isEqualTo(214);
    assertThat(fromSummary.steps()).hasSize(6);
    assertThat(fromSummary.sagaStatus()).isEqualTo("DANG_CHAY");
    assertThat(fromSummary.warnings())
        .containsExactly(
            "Có 214 minh chứng chưa được nghiệm thu sẽ không được tính điểm trong kỳ.",
            "Cây tổ chức có lệch mức Thấp.",
            "Có 3 hồ sơ lỗi khi tính điểm đang chờ xử lý.");

    UUID b1 = UUID.randomUUID();
    projections.upsertBatch(TENANT, order.id(), new LockBatch(b1, null, 600, "XONG", 1, NOW));
    projections.upsertBatch(
        TenantId.of(UUID.randomUUID()),
        order.id(),
        new LockBatch(UUID.randomUUID(), null, 999, "XONG", 1, NOW));
    projections.upsertBatch(
        TENANT, order.id(), new LockBatch(UUID.randomUUID(), null, 400, "LOI", 3, null));
    projections.upsertFailure(TENANT, order.id(), failure(UUID.randomUUID()));
    PeriodLockControlService.Overview fromBatches = service.overview(TENANT, period).orElseThrow();
    assertThat(fromBatches.totals()).isEqualTo(new PeriodLockControlService.Totals(1000, 600, 1));
    assertThat(service.batches(TENANT, period, "XONG", null, 21))
        .extracting(LockBatch::id)
        .containsExactly(b1);
    assertThat(service.batches(TENANT, period, null, null, 21)).hasSize(2);
    assertThat(new LockBatch(b1, null, 1, "XONG", 0, null).finished()).isTrue();

    orders.steps.put(
        order.id(), List.of(new LockStep(3, "Sinh mẫu", "LOI", NOW, null, Map.of(), null, null)));
    assertThat(service.overview(TENANT, period).orElseThrow().sagaStatus())
        .isEqualTo("LOI_CAN_XU_LY");

    PeriodLockOrder cancelled = finished(PeriodId.of(UUID.randomUUID()), LockOrderStatus.DA_HUY);
    assertThat(
            new PeriodLockControlService.Overview(
                    cancelled,
                    PeriodLockService.sixSteps(List.of()),
                    new PeriodLockControlService.Totals(0, 0, 0),
                    0,
                    List.of())
                .sagaStatus())
        .isEqualTo("DA_BU_TRU");
  }

  @Test
  void eventsDiagramAndCommandLookup() {
    service.pause(TENANT, mfa(STARTER), period, 2, null);
    service.resume(TENANT, mfa(STARTER), period, 3, null);
    List<LockEvent> events = service.events(TENANT, period, null, null, 21);
    assertThat(events).hasSize(2);
    assertThat(events.get(0).kind()).isEqualTo("TIEP_TUC");
    assertThat(events.get(0).actorId()).isEqualTo(STARTER);
    assertThat(service.events(TENANT, period, events.get(0).at(), events.get(0).id(), 21))
        .extracting(LockEvent::kind)
        .containsExactly("TAM_DUNG");

    orders.steps.put(
        order.id(),
        List.of(
            new LockStep(0, "a", "DAT", NOW, NOW, Map.of(), null, null),
            new LockStep(1, "b", "DAT_CO_CANH_BAO", NOW, NOW, Map.of(), null, null),
            new LockStep(2, "c", "DANG_CHAY", NOW, null, Map.of(), null, null),
            new LockStep(3, "d", "LOI", NOW, null, Map.of(), null, null),
            new LockStep(9, "z", "DAT", NOW, NOW, Map.of(), null, null)));
    LockSagaDiagram d = service.diagram(TENANT, period).orElseThrow();
    assertThat(d.completedActivityIds()).containsExactly("buoc0TienDieuKien", "buoc1KhoaKy");
    assertThat(d.activeActivityIds()).containsExactly("buoc2TinhDiem");
    assertThat(d.failedActivityIds()).containsExactly("buoc3SinhMau");
    assertThat(d.bpmnXml())
        .startsWith("<?xml")
        .contains("<serviceTask id=\"buoc4KySo\" name=\"Ký số lô\"/>")
        .contains("bpmnElement=\"luong_buoc5HoanTat_ketThuc\"")
        .doesNotContain("<script");
    assertThat(
            LockSagaDiagram.of(
                    List.of(new LockStep(-1, "x", "DAT", null, null, Map.of(), null, null)))
                .completedActivityIds())
        .isEmpty();

    UUID jobId = UUID.randomUUID();
    projections.commandRows.add(
        new LockProjectionRepository.CommandRow(
            jobId, order.id(), LockCommand.BU_TRU, "CHO", SECOND, NOW, NOW));
    assertThat(service.command(TENANT, jobId)).isPresent();
    assertThat(service.command(TENANT, UUID.randomUUID())).isEmpty();
    assertThat(LockCommand.TAM_DUNG.jobType()).isNull();
    assertThat(LockCommand.BU_TRU.label()).isEqualTo("Huỷ và bù trừ toàn bộ");
  }

  @Test
  void domainControlRules() {
    PeriodLockOrder o = running(period);
    assertThat(o.controllable()).isTrue();
    assertThatThrownBy(() -> o.approveCancel(SECOND, NOW))
        .isInstanceOf(PeriodLockOrder.IllegalStatusException.class);
    assertThatThrownBy(o::cancelled).isInstanceOf(PeriodLockOrder.IllegalStatusException.class);
    assertThat(o.requestCancel(ADMIN, REASON, NOW)).isTrue();
    assertThat(o.requestCancel(STARTER, REASON, NOW)).isFalse();
    assertThatThrownBy(() -> o.approveCancel(ADMIN, NOW))
        .isInstanceOf(PeriodLockOrder.SamePersonException.class);
    o.approveCancel(SECOND, NOW);
    assertThat(o.controllable()).isFalse();
    assertThatThrownBy(() -> o.requestPause(STARTER, NOW))
        .isInstanceOf(PeriodLockOrder.IllegalStatusException.class);
    o.cancelled();
    assertThat(o.status()).isEqualTo(LockOrderStatus.DA_HUY);
    assertThat(o.pauseRequested()).isFalse();
    PeriodLockOrder pending =
        PeriodLockOrder.request(UUID.randomUUID(), TENANT, period, "x", null, ADMIN, NOW, "CK-2");
    assertThat(pending.controllable()).isFalse();
    assertThatThrownBy(pending::resume).isInstanceOf(PeriodLockOrder.IllegalStatusException.class);
    assertThatThrownBy(() -> new LockCancelRequest(null, NOW, "x", null, null))
        .isInstanceOf(NullPointerException.class);
  }

  /** Bản chiếu và hộp lệnh trong bộ nhớ. */
  static final class Projections implements LockProjectionRepository {
    final List<Object[]> batchRows = new ArrayList<>();
    final List<Object[]> failureRows = new ArrayList<>();
    final List<LockEvent> events = new ArrayList<>();
    final List<String[]> commands = new ArrayList<>();
    final List<CommandRow> commandRows = new ArrayList<>();

    @Override
    public List<LockBatch> batches(
        TenantId tenantId, UUID lockOrderId, String status, UUID afterId, int fetchSize) {
      return batchRows.stream()
          .filter(r -> r[0].equals(tenantId) && r[1].equals(lockOrderId))
          .map(r -> (LockBatch) r[2])
          .filter(b -> status == null || status.equals(b.status()))
          .filter(b -> afterId == null || b.id().compareTo(afterId) > 0)
          .sorted(Comparator.comparing(LockBatch::id))
          .limit(fetchSize)
          .toList();
    }

    @Override
    public BatchTotals batchTotals(TenantId tenantId, UUID lockOrderId) {
      List<LockBatch> all = batches(tenantId, lockOrderId, null, null, Integer.MAX_VALUE);
      return new BatchTotals(
          all.size(),
          all.stream().mapToInt(LockBatch::profileCount).sum(),
          all.stream().filter(LockBatch::finished).mapToInt(LockBatch::profileCount).sum());
    }

    @Override
    public List<LockFailure> openFailures(
        TenantId tenantId, UUID lockOrderId, UUID afterProfileId, int fetchSize) {
      return failureRows.stream()
          .filter(r -> r[0].equals(lockOrderId) && r[2] == null)
          .map(r -> (LockFailure) r[1])
          .filter(f -> afterProfileId == null || f.profileId().compareTo(afterProfileId) > 0)
          .sorted(Comparator.comparing(LockFailure::profileId))
          .limit(fetchSize)
          .toList();
    }

    @Override
    public int countOpenFailures(TenantId tenantId, UUID lockOrderId) {
      return openFailures(tenantId, lockOrderId, null, Integer.MAX_VALUE).size();
    }

    @Override
    public List<UUID> openFailuresAmong(
        TenantId tenantId, UUID lockOrderId, Collection<UUID> profileIds) {
      return openFailures(tenantId, lockOrderId, null, Integer.MAX_VALUE).stream()
          .map(LockFailure::profileId)
          .filter(profileIds::contains)
          .toList();
    }

    @Override
    public List<LockEvent> events(
        TenantId tenantId, UUID lockOrderId, Instant beforeAt, UUID beforeId, int fetchSize) {
      List<LockEvent> newestFirst = new ArrayList<>(events);
      java.util.Collections.reverse(newestFirst);
      int start = 0;
      if (beforeId != null) {
        for (int i = 0; i < newestFirst.size(); i++) {
          if (newestFirst.get(i).id().equals(beforeId)) {
            start = i + 1;
          }
        }
      }
      return newestFirst.subList(start, Math.min(newestFirst.size(), start + fetchSize));
    }

    @Override
    public void appendEvent(TenantId tenantId, UUID lockOrderId, LockEvent event) {
      events.add(event);
    }

    @Override
    public void upsertBatch(TenantId tenantId, UUID lockOrderId, LockBatch batch) {
      batchRows.add(new Object[] {tenantId, lockOrderId, batch});
    }

    @Override
    public void upsertFailure(TenantId tenantId, UUID lockOrderId, LockFailure failure) {
      failureRows.add(new Object[] {lockOrderId, failure, null});
    }

    @Override
    public int resolveFailures(
        TenantId tenantId, UUID lockOrderId, Collection<UUID> profileIds, Instant at) {
      int n = 0;
      for (Object[] r : failureRows) {
        if (r[0].equals(lockOrderId)
            && r[2] == null
            && profileIds.contains(((LockFailure) r[1]).profileId())) {
          r[2] = at;
          n++;
        }
      }
      return n;
    }

    @Override
    public Optional<CommandRow> command(TenantId tenantId, UUID commandId) {
      return commandRows.stream().filter(c -> c.id().equals(commandId)).findFirst();
    }

    @Override
    public int finishCommands(
        TenantId tenantId, UUID lockOrderId, LockCommand command, String status, Instant at) {
      int n = 0;
      for (String[] c : commands) {
        if (c[0].equals(lockOrderId.toString()) && c[1].equals(command.name())) {
          c[2] = status;
          n++;
        }
      }
      return n;
    }
  }
}
