package vn.viettel.okrkpi.task.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskStatusChangedEvent;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.ChangeRequestRow;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTable;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTables;

/** Dịch vụ chuyển trạng thái, guard CEL thật và ghim phiên bản bảng theo kỳ (CORE-02-13). */
class TaskTransitionServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

  private final CelGuardEvaluator guards = new CelGuardEvaluator(new CelEngine());
  private final LifecycleFakes.Repo repo = new LifecycleFakes.Repo();
  private final LifecycleFakes.Periods periods = new LifecycleFakes.Periods();
  private final List<TaskStatusChangedEvent> outbox = new ArrayList<>();
  private final List<Object> published = new ArrayList<>();
  private final UUID period = UUID.randomUUID();
  private final UUID assigner = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private final TaskActor manager = new TaskActor(assigner, Set.of("manager"));

  private TaskTransitionService service(TransitionTable table) {
    return new TaskTransitionService(
        repo,
        (t, p) -> table,
        guards,
        periods,
        outbox::add,
        null,
        published::add,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private TaskTransitionService service() {
    return service(TransitionTables.defaults());
  }

  private void given(TaskStatus status) {
    repo.rows.put(taskId, LifecycleFakes.task(taskId, period, status, assigner));
  }

  @Test
  void tiepTucGhiNhatKyOutboxVaSuKien() {
    given(TaskStatus.TAM_DUNG);
    TaskTransitionService.Transitioned r =
        service().transition(TENANT, taskId, new TaskCommand.Resume(), manager, 3L);

    assertThat(r.task().status()).isEqualTo(TaskStatus.DANG_THUC_HIEN);
    assertThat(r.task().rowVersion()).isEqualTo(4);
    assertThat(repo.log)
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.transition()).isEqualTo("RESUME");
              assertThat(l.stateMachineVersion()).isEqualTo(TransitionTables.DEFAULT_VERSION);
              assertThat(l.actorId()).isEqualTo(assigner);
            });
    assertThat(repo.beforeAfter.get(0))
        .contains("\"before\":{\"status\":\"TAM_DUNG\",\"row_version\":3}")
        .contains("\"after\":{\"status\":\"DANG_THUC_HIEN\",\"row_version\":4}");
    assertThat(outbox)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.fromStatus()).isEqualTo("TAM_DUNG");
              assertThat(e.toStatus()).isEqualTo("DANG_THUC_HIEN");
              assertThat(e.aggregateVersion()).isEqualTo(4);
              assertThat(e.occurredAt()).isEqualTo(NOW);
            });
    assertThat(published)
        .filteredOn(TaskStatusChangedEvent.class::isInstance)
        .containsExactlyElementsOf(outbox);
    // CORE-02-12: kèm sự kiện trong tiến trình TrangThaiNhiemVuDoi.
    assertThat(published)
        .filteredOn(vn.viettel.okrkpi.task.api.events.TrangThaiNhiemVuDoi.class::isInstance)
        .hasSize(1);
    assertThat(repo.updates.get(0).completedAt()).isNull();
    assertThat(service().stateLog(TENANT, r.task())).hasSize(1);
    assertThat(service().find(TENANT, taskId)).isPresent();
  }

  @Test
  void hoanThanhGhiThoiDiemVaHuyGhiLyDo() {
    given(TaskStatus.CHO_NGHIEM_THU);
    service().transition(TENANT, taskId, new TaskCommand.ApproveAcceptance(null), manager, null);
    assertThat(repo.updates.get(0).completedAt()).isEqualTo(NOW);

    repo.rows.clear();
    given(TaskStatus.TAM_DUNG);
    repo.change = new ChangeRequestRow("HUY", "DA_DUYET");
    service().transition(TENANT, taskId, new TaskCommand.Cancel("Không còn nhu cầu"), manager, 3L);
    assertThat(repo.updates.get(1).cancelledReason()).isEqualTo("Không còn nhu cầu");
    assertThat(repo.updates.get(1).to()).isEqualTo(TaskStatus.HUY);
  }

  @Test
  void anhXaTuChoiSangMaLoi() {
    given(TaskStatus.TAM_DUNG);
    assertThatThrownBy(
            () -> service().transition(TENANT, taskId, new TaskCommand.Resume(), manager, 2L))
        .isInstanceOf(VersionConflictException.class);
    assertThat(
            code(
                () ->
                    service()
                        .transition(TENANT, taskId, new TaskCommand.Submit(null), manager, 3L)))
        .isEqualTo(TaskTransitionService.INVALID_TRANSITION);
    TaskActor member = new TaskActor(UUID.randomUUID(), Set.of("member"));
    assertThat(
            code(() -> service().transition(TENANT, taskId, new TaskCommand.Resume(), member, 3L)))
        .isEqualTo(TaskTransitionService.ROLE_NOT_ALLOWED);
    // Người tiếp tục không phải người giao: guard sai.
    TaskActor other = new TaskActor(UUID.randomUUID(), Set.of("manager"));
    assertThat(
            code(() -> service().transition(TENANT, taskId, new TaskCommand.Resume(), other, 3L)))
        .isEqualTo(TaskTransitionService.GUARD_FAILED);
    assertThat(
            code(
                () ->
                    service().transition(TENANT, taskId, new TaskCommand.Cancel(""), manager, 3L)))
        .isEqualTo("COMMON-422-VALIDATION");
    periods.locked.add(period);
    assertThat(
            code(() -> service().transition(TENANT, taskId, new TaskCommand.Resume(), manager, 3L)))
        .isEqualTo(TaskTransitionService.PERIOD_LOCKED);
    assertThat(repo.log).isEmpty();
    assertThat(outbox).isEmpty();

    repo.rows.clear();
    given(TaskStatus.HUY);
    assertThat(
            code(() -> service().transition(TENANT, taskId, new TaskCommand.Resume(), manager, 3L)))
        .isEqualTo(TaskTransitionService.INVALID_TRANSITION);
    assertThatThrownBy(
            () ->
                service()
                    .transition(TENANT, UUID.randomUUID(), new TaskCommand.Resume(), manager, 3L))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void nguCanhGuardTheoKhoiNguCanh() {
    given(TaskStatus.DANG_THUC_HIEN);
    TaskTransitionService s = service();
    Map<String, Object> a = s.activation(TENANT, repo.rows.get(taskId), manager);
    assertThat(asMap(a.get("task")))
        .containsEntry("chu_tri_id", "")
        .containsEntry("nguoi_giao_id", assigner.toString())
        .containsEntry("nguoi_nghiem_thu_id", "")
        .containsEntry("ke_hoach", 2L)
        .containsEntry("so_san_pham_da_nop", 2L);
    assertThat(asMap(a.get("change_request")))
        .containsEntry("request_type", "")
        .containsEntry("status", "");
    assertThat(guards.test("task.so_san_pham_da_nop >= task.ke_hoach", a)).isTrue();

    UUID lead = UUID.randomUUID();
    repo.lead = lead;
    repo.change = new ChangeRequestRow(null, null);
    TaskTransitionService withItems =
        new TaskTransitionService(
            repo,
            (t, p) -> TransitionTables.defaults(),
            guards,
            periods,
            e -> {},
            (t, id) -> List.of(Map.of("status", "NHAP"), Map.of("status", "DA_XAC_NHAN")),
            null,
            Clock.systemUTC());
    Map<String, Object> b = withItems.activation(TENANT, repo.rows.get(taskId), manager);
    assertThat(asMap(b.get("task")))
        .containsEntry("chu_tri_id", lead.toString())
        .containsEntry("so_san_pham_da_nop", 1L);
    assertThat(guards.test("deliverables.all(d, d.status == 'DA_XAC_NHAN')", b)).isFalse();
    // Lỗi lúc chạy (khoá không có) coi như chưa đạt.
    assertThat(guards.test("task.khong_co > 1", b)).isFalse();
  }

  @Test
  void guardMotDieuKienDuoiMotMiliGiay() {
    given(TaskStatus.DANG_THUC_HIEN);
    Map<String, Object> a = service().activation(TENANT, repo.rows.get(taskId), manager);
    var machine =
        new vn.viettel.okrkpi.task.domain.lifecycle.TaskStateMachine(
            TransitionTables.defaults(), guards);
    TaskCommand submit = new TaskCommand.Submit(null);
    TaskActor member = new TaskActor(assigner, Set.of("member"));
    for (int i = 0; i < 2_000; i++) {
      machine.apply(TaskStatus.DANG_THUC_HIEN, submit, member, true, () -> a);
    }
    int n = 5_000;
    long[] ns = new long[n];
    for (int i = 0; i < n; i++) {
      long t0 = System.nanoTime();
      machine.apply(TaskStatus.DANG_THUC_HIEN, submit, member, true, () -> a);
      ns[i] = System.nanoTime() - t0;
    }
    Arrays.sort(ns);
    assertThat(ns[(int) (n * 0.99)]).isLessThan(Duration.ofMillis(1).toNanos());
  }

  @Test
  void bangGhimTheoPhienBanGoiCuaKy() {
    UUID periodA = UUID.randomUUID();
    UUID periodB = UUID.randomUUID();
    ResolvedPack v1 = pack("2.1.0");
    ResolvedPack v2 = pack("3.0.0");
    Map<String, Object> v2Table =
        Map.of(
            "transitions",
            List.of(
                Map.of(
                    "from",
                    "DA_GIAO",
                    "command",
                    "ACCEPT",
                    "to",
                    "DANG_THUC_HIEN",
                    "allowedRoles",
                    List.of("member"))));
    AtomicInteger resolveCalls = new AtomicInteger();
    PolicyQueryApi api =
        new PolicyQueryApi() {
          @Override
          public Optional<PolicyFormulaView> findActivePolicy(
              TenantId tenantId, PeriodId periodId, String code) {
            return Optional.empty();
          }

          @Override
          public Optional<ResolvedPack> resolve(
              TenantId t, PeriodId p, String flow, UUID group, UUID org) {
            resolveCalls.incrementAndGet();
            if (!"DANG".equals(flow)) {
              return Optional.empty();
            }
            if (p.value().equals(periodA)) {
              return Optional.of(v1);
            }
            return p.value().equals(periodB) ? Optional.of(v2) : Optional.empty();
          }

          @Override
          public Optional<Map<String, Object>> taskLifecycle(TenantId t, UUID versionId) {
            return versionId.equals(v2.versionId()) ? Optional.of(v2Table) : Optional.empty();
          }
        };
    PolicyBackedTransitionTableProvider provider =
        new PolicyBackedTransitionTableProvider(api, guards, Duration.ofMinutes(5));

    TransitionTable a = provider.tableFor(TENANT, periodA);
    TransitionTable b = provider.tableFor(TENANT, periodB);
    assertThat(a.find(TaskStatus.DANG_THUC_HIEN, "SUBMIT")).isPresent();
    assertThat(b.find(TaskStatus.DANG_THUC_HIEN, "SUBMIT")).isEmpty();
    assertThat(b.policyVersion()).isEqualTo("quy-dinh-moi@3.0.0");
    assertThat(provider.tableFor(TENANT, UUID.randomUUID()).policyVersion())
        .isEqualTo(TransitionTables.DEFAULT_VERSION);
    int calls = resolveCalls.get();
    assertThat(provider.tableFor(TENANT, periodB)).isSameAs(b);
    assertThat(resolveCalls.get()).isEqualTo(calls);

    // Kỳ B ghim v2: SUBMIT bị từ chối, kỳ A vẫn chuyển được.
    UUID ta = UUID.randomUUID();
    UUID tb = UUID.randomUUID();
    repo.rows.put(ta, LifecycleFakes.task(ta, periodA, TaskStatus.DANG_THUC_HIEN, assigner));
    repo.rows.put(tb, LifecycleFakes.task(tb, periodB, TaskStatus.DANG_THUC_HIEN, assigner));
    TaskTransitionService s =
        new TaskTransitionService(
            repo, provider, guards, periods, outbox::add, null, null, Clock.systemUTC());
    TaskActor member = new TaskActor(assigner, Set.of("member"));
    assertThat(s.transition(TENANT, ta, new TaskCommand.Submit(null), member, 3L).task().status())
        .isEqualTo(TaskStatus.CHO_NGHIEM_THU);
    assertThat(code(() -> s.transition(TENANT, tb, new TaskCommand.Submit(null), member, 3L)))
        .isEqualTo(TaskTransitionService.INVALID_TRANSITION);

    String longCode = "a".repeat(40);
    assertThat(
            PolicyBackedTransitionTableProvider.version(
                new ResolvedPack(
                    UUID.randomUUID(),
                    longCode,
                    UUID.randomUUID(),
                    "1.0.0",
                    "h",
                    null,
                    UUID.randomUUID(),
                    true)))
        .hasSize(32)
        .endsWith("@1.0.0");
  }

  private static ResolvedPack pack(String version) {
    return new ResolvedPack(
        UUID.randomUUID(),
        "quy-dinh-moi",
        UUID.randomUUID(),
        version,
        "hash",
        null,
        UUID.randomUUID(),
        true);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return new HashMap<>((Map<String, Object>) o);
  }

  private static String code(Runnable r) {
    try {
      r.run();
    } catch (BusinessException e) {
      return e.errorCode().code();
    }
    throw new AssertionError("không ném BusinessException");
  }

  @Test
  void huyNhapNgoaiBangChuyen() {
    given(TaskStatus.NHAP);
    TaskTransitionService.Transitioned r =
        service().cancelDraft(TENANT, taskId, 3L, "TU_CHOI_DANG_KY", manager, "Trùng việc");
    assertThat(r.task().status()).isEqualTo(TaskStatus.HUY);
    assertThat(repo.log)
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.transition()).isEqualTo("TU_CHOI_DANG_KY");
              assertThat(l.reason()).isEqualTo("Trùng việc");
            });
    assertThat(outbox).singleElement().satisfies(e -> assertThat(e.toStatus()).isEqualTo("HUY"));
    assertThat(published).hasSize(2);

    assertThatThrownBy(
            () -> service().cancelDraft(TENANT, taskId, 9L, "TU_CHOI_DANG_KY", manager, "x"))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () -> service().cancelDraft(TENANT, taskId, r.task().rowVersion(), "X", manager, "x"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service().cancelDraft(TENANT, UUID.randomUUID(), 1L, "X", manager, "x"))
        .isInstanceOf(NoSuchElementException.class);
  }
}
