package vn.viettel.okrkpi.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BatchJob;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.HistoryAction;
import vn.viettel.okrkpi.workflow.domain.OpenTask;
import vn.viettel.okrkpi.workflow.domain.ProcessHistory;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;

/** Use case hộp việc, hoàn thành, duyệt hàng loạt, lịch sử và bảng chiếu (DV-06-02). */
class ApplicationServicesTest {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-000000000001");
  private static final UUID UNIT = UUID.fromString("0192f1a2-3333-7ccc-8ddd-000000000a01");
  private static final UUID ME = UUID.fromString("0192f1a2-2222-7aaa-8bbb-000000000b04");
  private static final UUID AUTHOR = UUID.fromString("0192f1a2-2222-7aaa-8bbb-000000000b05");
  private static final Instant NOW = Instant.parse("2027-01-07T03:00:00Z");

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private Fakes.Caller caller;
  private Fakes.Policy policy;
  private Fakes.Projections projections;
  private Fakes.Engine engine;
  private CompleteTaskService completer;

  @BeforeEach
  void setUp() {
    caller = new Fakes.Caller(new Approver(TENANT, ME, Set.of("leader"), UNIT));
    policy = new Fakes.Policy();
    projections = new Fakes.Projections();
    engine = new Fakes.Engine();
    completer = new CompleteTaskService(engine, caller, policy);
  }

  private OpenTask task(String id, UUID assignee, int revision) {
    return new OpenTask(
        id,
        TENANT,
        assignee,
        assignee == null ? List.of(new CandidateGroup(TENANT, "leader", UNIT)) : List.of(),
        revision,
        new BusinessKey("OKR_OBJECTIVE", UUID.randomUUID()),
        AUTHOR);
  }

  // ------------------------------------------------------------------ bảng chiếu

  @Test
  void bangChieuTaoGiaoLaiVaXoaTheoSuKienEngine() {
    TaskProjectionService service = new TaskProjectionService(projections);
    String key = "PLAN:" + UUID.randomUUID();
    UUID period = UUID.randomUUID();
    service.onCreated(
        new EngineTaskEvent(
            "t1",
            TENANT.toString(),
            null,
            List.of(
                "khong-dung-khuon",
                UUID.randomUUID() + ":leader:" + UNIT,
                TENANT + ":leader:" + UNIT),
            key,
            "duyet-ke-hoach-quy",
            "  Duyệt   kế hoạch\tquý I  ",
            period.toString(),
            NOW.plusSeconds(3600),
            NOW,
            1));
    TaskProjection created = projections.rows.get("t1");
    assertThat(created.title()).isEqualTo("Duyệt kế hoạch quý I");
    assertThat(created.candidateRole()).isEqualTo("leader");
    assertThat(created.candidateUnitId()).isEqualTo(UNIT);
    assertThat(created.periodId()).isEqualTo(period);
    assertThat(created.assigneeId()).isNull();
    assertThat(created.businessKey().value()).isEqualTo(key);

    service.onAssigned(
        new EngineTaskEvent(
            "t1", TENANT.toString(), ME.toString(), null, null, null, null, null, null, null, 2));
    assertThat(projections.rows.get("t1").assigneeId()).isEqualTo(ME);
    assertThat(projections.rows.get("t1").rowVersion()).isEqualTo(2);

    service.onRemoved(
        new EngineTaskEvent(
            "t1", TENANT.toString(), null, null, null, null, null, null, null, null, 0));
    assertThat(projections.rows).isEmpty();
  }

  @Test
  void bangChieuBoQuaTaskKhongCoTenantHoacKhoaNghiepVu() {
    TaskProjectionService service = new TaskProjectionService(projections);
    service.onCreated(
        new EngineTaskEvent(
            "a", "", null, null, "PLAN:" + UUID.randomUUID(), null, null, null, null, NOW, 1));
    service.onCreated(
        new EngineTaskEvent(
            "b", TENANT.toString(), null, null, "khong-hop-le", null, null, null, null, NOW, 1));
    service.onCreated(
        new EngineTaskEvent("c", "khong-uuid", null, null, null, null, null, null, null, null, 0));
    service.onAssigned(
        new EngineTaskEvent("a", null, ME.toString(), null, null, null, null, null, null, null, 2));
    service.onRemoved(
        new EngineTaskEvent("a", " ", null, null, null, null, null, null, null, null, 0));
    assertThat(projections.rows).isEmpty();

    service.onCreated(
        new EngineTaskEvent(
            "d",
            TENANT.toString(),
            "khong-uuid",
            null,
            "PLAN:" + UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null,
            0));
    TaskProjection d = projections.rows.get("d");
    assertThat(d.title()).isEqualTo("Việc cần xử lý");
    assertThat(d.assigneeId()).isNull();
    assertThat(d.rowVersion()).isEqualTo(1);
    assertThat(d.createdAt()).isEqualTo(Instant.EPOCH);
  }

  // ------------------------------------------------------------------ hộp việc

  @Test
  void hopViecPhanTrangConTroVaDemQuaHan() {
    for (int i = 0; i < 5; i++) {
      projections.upsert(
          new TaskProjection(
              "t" + i,
              TENANT,
              ME,
              null,
              null,
              new BusinessKey("OKR_OBJECTIVE", UUID.randomUUID()),
              "duyet-okr",
              "Việc " + i,
              null,
              i == 4 ? null : NOW.plus(Duration.ofHours(i * 6L - 6)),
              NOW.minusSeconds(60),
              1));
    }
    CursorCodec cursors = new CursorCodec(new byte[32], Duration.ofHours(1), clock::instant);
    InboxService service = new InboxService(projections, caller, policy, cursors, clock);

    InboxService.InboxPage first = service.list(null, null, 2, null);
    assertThat(first.items()).extracting(TaskProjection::taskId).containsExactly("t0", "t1");
    assertThat(first.nextCursor()).isNotNull();
    assertThat(first.counts().total()).isEqualTo(5);
    assertThat(first.counts().overdue()).isEqualTo(1);
    assertThat(first.counts().dueToday()).isGreaterThanOrEqualTo(1);
    assertThat(policy.calls).containsExactly("xem");

    InboxService.InboxPage second = service.list(null, null, 2, first.nextCursor());
    assertThat(second.items()).extracting(TaskProjection::taskId).containsExactly("t2", "t3");
    InboxService.InboxPage third = service.list(null, null, 2, second.nextCursor());
    assertThat(third.items()).extracting(TaskProjection::taskId).containsExactly("t4");
    assertThat(third.nextCursor()).isNull();
    assertThat(projections.lastAfter.dueAt()).isNotNull();

    caller.approver = new Approver(TENANT, ME, Set.of(), null);
    assertThat(service.list("OKR_OBJECTIVE", UUID.randomUUID(), null, null).items()).hasSize(5);
    assertThat(policy.resources.get(policy.resources.size() - 1).ownerUnitId()).isNull();
  }

  @Test
  void hopViecConTroSauViecKhongHan() {
    for (String id : List.of("a", "b", "c")) {
      projections.upsert(
          new TaskProjection(
              id,
              TENANT,
              ME,
              null,
              null,
              new BusinessKey("PLAN", UUID.randomUUID()),
              null,
              "Việc",
              null,
              null,
              NOW,
              1));
    }
    CursorCodec cursors = new CursorCodec(new byte[32], Duration.ofHours(1), clock::instant);
    InboxService service = new InboxService(projections, caller, policy, cursors, clock);
    InboxService.InboxPage first = service.list(null, null, 1, null);
    InboxService.InboxPage second = service.list(null, null, 1, first.nextCursor());
    assertThat(projections.lastAfter.dueAt()).isNull();
    assertThat(second.items()).extracting(TaskProjection::taskId).containsExactly("b");
  }

  // ------------------------------------------------------------------ hoàn thành

  @Test
  void hoanThanhViecDuocGiaoKiemPhienBanVaGoiOpa() {
    engine.open.put("t1", task("t1", ME, 3));
    CompleteTaskService.Result result =
        completer.complete(new CompleteTaskService.Command("t1", 3, Decision.DUYET, "  Đồng ý.  "));
    assertThat(result.processEnded()).isTrue();
    assertThat(result.decision()).isEqualTo(Decision.DUYET);
    assertThat(engine.calls).containsExactly("t1:DUYET:Đồng ý.");
    assertThat(policy.calls).containsExactly("duyet");
    AuthzResource resource = policy.resources.get(0);
    assertThat(resource.participants()).containsEntry("initiator", List.of(AUTHOR.toString()));
    assertThat(resource.assigneeIds()).containsExactly(ME.toString());
    assertThat(resource.type()).isEqualTo("okr_objective");
    assertThat(resource.ownerUnitId()).isEqualTo(UNIT.toString());
  }

  @Test
  void hoanThanhViecUngVienVaTraLai() {
    engine.open.put("t2", task("t2", null, 1));
    completer.complete(new CompleteTaskService.Command("t2", 1, Decision.TRA_LAI, "Bổ sung KR."));
    assertThat(policy.calls).containsExactly("tra-lai");
    assertThat(policy.resources.get(0).ownerUnitId()).isEqualTo(UNIT.toString());

    OpenTask noInitiator =
        new OpenTask(
            "t3", TENANT, ME, List.of(), 1, new BusinessKey("PLAN", UUID.randomUUID()), null);
    engine.open.put("t3", noInitiator);
    caller.approver = new Approver(TENANT, ME, Set.of(), null);
    completer.completeBatchItem(new CompleteTaskService.Command("t3", 1, Decision.DUYET, " "));
    assertThat(policy.calls).containsExactly("tra-lai", "duyet-lo");
    assertThat(policy.resources.get(1).participants()).isNull();
    assertThat(policy.resources.get(1).ownerUnitId()).isNull();
    assertThat(engine.calls).last().isEqualTo("t3:DUYET:null");
  }

  @Test
  void hoanThanhTuChoiDungMaLoi() {
    engine.open.put("t1", task("t1", ME, 2));
    engine.open.put("t9", task("t9", UUID.randomUUID(), 1));
    engine.completed.add("done");

    assertThatThrownBy(
            () ->
                completer.complete(new CompleteTaskService.Command("t1", 1, Decision.DUYET, null)))
        .isInstanceOfSatisfying(
            VersionConflictException.class,
            e -> {
              assertThat(e.module()).isEqualTo("WORKFLOW");
              assertThat(e.currentVersion()).isEqualTo(2L);
            });
    assertThatThrownBy(
            () ->
                completer.complete(
                    new CompleteTaskService.Command("done", 1, Decision.DUYET, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("WORKFLOW-409-TASK-COMPLETED"));
    assertThatThrownBy(
            () ->
                completer.complete(
                    new CompleteTaskService.Command("nope", 1, Decision.DUYET, null)))
        .isInstanceOf(WorkflowNotFoundException.class);
    assertThatThrownBy(
            () ->
                completer.complete(new CompleteTaskService.Command("t9", 1, Decision.DUYET, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(
            () ->
                completer.complete(new CompleteTaskService.Command("t1", 2, Decision.TRA_LAI, " ")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION");
              assertThat(e.msg()).isEqualTo("MSG-02-10");
              assertThat(e.errors()).extracting(f -> f.field()).containsExactly("comment");
            });
    assertThatThrownBy(
            () ->
                completer.complete(
                    new CompleteTaskService.Command("t1", 2, Decision.DUYET, "a".repeat(2001))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION"));

    policy.deny = "WORKFLOW-403-SELF-APPROVAL";
    assertThatThrownBy(
            () ->
                completer.complete(new CompleteTaskService.Command("t1", 2, Decision.DUYET, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("WORKFLOW-403-SELF-APPROVAL"));
    assertThat(engine.calls).isEmpty();
  }

  // ------------------------------------------------------------------ duyệt hàng loạt

  private BatchCompleteService batchService(Fakes.Batches store, Fakes.Dispatcher dispatcher) {
    return new BatchCompleteService(
        store, completer, caller, policy, dispatcher, new Fakes.NoTransactions(), clock);
  }

  @Test
  void loGhiKetQuaTungMucLoiKhongLamHongMucKhac() {
    engine.open.put("ok", task("ok", ME, 1));
    engine.open.put("lech", task("lech", ME, 5));
    engine.open.put("nguoi-khac", task("nguoi-khac", UUID.randomUUID(), 1));
    engine.open.put("loi", task("loi", ME, 1));
    engine.onComplete =
        t -> {
          if (t.taskId().equals("loi")) {
            throw new IllegalStateException("engine lỗi");
          }
          return new vn.viettel.okrkpi.workflow.application.port.TaskEngine.Completion(NOW, false);
        };
    Fakes.Batches store = new Fakes.Batches();
    BatchCompleteService service = batchService(store, new Fakes.Dispatcher());

    BatchJob accepted =
        service.submit(
            new BatchCompleteService.Command(
                Decision.DUYET,
                "Đồng ý.",
                List.of(
                    new BatchCompleteService.Item("ok", 1),
                    new BatchCompleteService.Item("lech", 1),
                    new BatchCompleteService.Item("nguoi-khac", 1),
                    new BatchCompleteService.Item("mat", 1),
                    new BatchCompleteService.Item("loi", 1))));
    assertThat(accepted.status()).isEqualTo(BatchJob.Status.CHO);
    BatchJob done = service.find(TENANT, accepted.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(BatchJob.Status.HOAN_TAT);
    assertThat(done.results())
        .extracting(BatchJob.ItemResult::status)
        .containsExactly(200, 409, 403, 404, 500);
    assertThat(done.results().get(1).code()).isEqualTo("WORKFLOW-409-VERSION-CONFLICT");
    assertThat(done.results().get(2).code()).isEqualTo("COMMON-403-OUT-OF-SCOPE");
    assertThat(done.doneItems()).isEqualTo(1);
    assertThat(done.failedItems()).isEqualTo(4);
    assertThat(store.history).startsWith(BatchJob.Status.CHO, BatchJob.Status.DANG_CHAY);
    assertThat(policy.calls).first().isEqualTo("duyet-lo");
    assertThat(service.find(UUID.randomUUID(), accepted.id())).isEmpty();
  }

  @Test
  void loTraLaiVaLoThatBaiKhiKhongGhiDuocTrangThai() {
    engine.open.put("a", task("a", ME, 1));
    Fakes.Batches failing =
        new Fakes.Batches() {
          int updates;

          @Override
          public void update(BatchJob job) {
            if (++updates == 2) {
              throw new IllegalStateException("CSDL lỗi");
            }
            super.update(job);
          }
        };
    BatchCompleteService service = batchService(failing, new Fakes.Dispatcher());
    BatchJob accepted =
        service.submit(
            new BatchCompleteService.Command(
                Decision.TRA_LAI, "Bổ sung.", List.of(new BatchCompleteService.Item("a", 1))));
    assertThat(failing.jobs.get(accepted.id()).status()).isEqualTo(BatchJob.Status.THAT_BAI);
    assertThat(policy.calls).first().isEqualTo("tra-lai");
  }

  @Test
  void loTuChoiQua200MucHoacRongHoacThieuLyDo() {
    BatchCompleteService service = batchService(new Fakes.Batches(), new Fakes.Dispatcher());
    List<BatchCompleteService.Item> many = new ArrayList<>();
    IntStream.range(0, 201).forEach(i -> many.add(new BatchCompleteService.Item("t" + i, 1)));
    assertThatThrownBy(
            () -> service.submit(new BatchCompleteService.Command(Decision.DUYET, null, many)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("WORKFLOW-422-BATCH-TOO-LARGE"));
    assertThatThrownBy(
            () -> service.submit(new BatchCompleteService.Command(Decision.DUYET, null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.submit(
                    new BatchCompleteService.Command(
                        Decision.TRA_LAI, null, List.of(new BatchCompleteService.Item("a", 1)))))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.msg()).isEqualTo("MSG-02-10"));
    caller.approver = new Approver(TENANT, ME, Set.of(), null);
    policy.deny = "COMMON-403-OUT-OF-SCOPE";
    assertThatThrownBy(
            () ->
                service.submit(
                    new BatchCompleteService.Command(
                        Decision.DUYET, null, List.of(new BatchCompleteService.Item("a", 1)))))
        .isInstanceOf(BusinessException.class);
  }

  // ------------------------------------------------------------------ lịch sử

  @Test
  void lichSuKiemQuyenDocVaKhongLoTenantKhac() {
    Fakes.Histories reader = new Fakes.Histories();
    BusinessKey key = new BusinessKey("OKR_OBJECTIVE", UUID.randomUUID());
    reader.byKey.put(
        TENANT + "|" + key.value(),
        new ProcessHistory(
            key,
            "duyet-okr",
            1,
            false,
            AUTHOR,
            List.of(
                new ProcessHistory.Entry(NOW, HistoryAction.TAO_TIEN_TRINH, AUTHOR, null, null))));
    HistoryQueryService service = new HistoryQueryService(reader, caller, policy);

    assertThat(service.get(key.value()).templateCode()).isEqualTo("duyet-okr");
    AuthzResource resource = policy.resources.get(0);
    assertThat(resource.participants()).isEqualTo(Map.of("initiator", List.of(AUTHOR.toString())));
    assertThat(resource.assigneeIds()).containsExactly(AUTHOR.toString());

    assertThatThrownBy(() -> service.get("sai-dang")).isInstanceOf(WorkflowNotFoundException.class);
    assertThatThrownBy(() -> service.get("PLAN:" + UUID.randomUUID()))
        .isInstanceOf(WorkflowNotFoundException.class);

    BusinessKey noAuthor = new BusinessKey("PLAN", UUID.randomUUID());
    reader.byKey.put(
        TENANT + "|" + noAuthor.value(),
        new ProcessHistory(noAuthor, null, null, true, null, List.of()));
    caller.approver = new Approver(TENANT, ME, Set.of(), null);
    assertThat(service.get(noAuthor.value()).ended()).isTrue();
    assertThat(policy.resources.get(1).participants()).isNull();
  }

  @Test
  void chinhSachOpaKhongLamGiKhiDuocPhep() {
    OpaApprovalAccessPolicy opa = new OpaApprovalAccessPolicy();
    AuthzResource r = AuthzResource.builder().type("x").build();
    opa.checkView(r);
    opa.checkApprove(r);
    opa.checkBatchApprove(r);
    opa.checkReturn(r);
    assertThat(new WorkflowNotFoundException().getMessage()).isEqualTo("WORKFLOW-NOT-FOUND");
    assertThat(
            new EngineTaskEvent("t", null, null, null, null, null, null, null, null, null, 0)
                .candidateGroups())
        .isEmpty();
  }
}
