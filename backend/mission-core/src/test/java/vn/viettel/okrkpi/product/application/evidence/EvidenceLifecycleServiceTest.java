package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceLifecycleRepository;
import vn.viettel.okrkpi.product.domain.evidence.DeliverableRollup;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceFixtures;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceStatus;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceVersion;
import vn.viettel.okrkpi.product.domain.evidence.TaskInterlock;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;

/** Liên động minh chứng với nhiệm vụ qua {@link TaskApi} (CORE-03-02, BR-07-10). */
class EvidenceLifecycleServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID TASK = UUID.randomUUID();
  static final EvidenceActor MEMBER = new EvidenceActor(UUID.randomUUID(), Set.of("member"));
  static final EvidenceActor REVIEWER = new EvidenceActor(UUID.randomUUID(), Set.of("manager"));

  final Repo repo = new Repo();
  final Tasks tasks = new Tasks();
  final Periods periods = new Periods();
  final EvidenceLifecycleService service =
      new EvidenceLifecycleService(
          repo, periods, tasks, Clock.fixed(Instant.parse("2027-03-12T03:00:00Z"), ZoneOffset.UTC));

  @Test
  void duSanPhamNopThiNhiemVuChoNghiemThuDuXacNhanThiHoanThanh() {
    UUID d1 = repo.deliverable(BigDecimal.ONE);
    UUID d2 = repo.deliverable(BigDecimal.ONE);
    UUID v1 = repo.version(d1, EvidenceStatus.NHAP);
    UUID v2 = repo.version(d2, EvidenceStatus.NHAP);

    var r1 = service.submitVersion(TENANT, v1, 0, MEMBER);
    assertThat(r1.version().status()).isEqualTo(EvidenceStatus.DA_NOP);
    assertThat(r1.taskRequest()).isEmpty();
    assertThat(tasks.calls).isEmpty();

    var r2 = service.submitVersion(TENANT, v2, 0, MEMBER);
    assertThat(r2.taskRequest()).contains(TaskInterlock.TaskRequest.SUBMIT);
    assertThat(tasks.calls)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.command()).isEqualTo(TaskApi.Command.SUBMIT);
              assertThat(c.deliverables()).hasSize(2);
              assertThat(c.actorId()).isEqualTo(MEMBER.personId());
            });
    assertThat(tasks.status).isEqualTo("CHO_NGHIEM_THU");

    service.openReview(TENANT, v1, 1, REVIEWER);
    service.openReview(TENANT, v2, 1, REVIEWER);
    service.acceptVersion(TENANT, v1, 2, REVIEWER);
    assertThat(repo.deliverableStatus.get(d1)).isEqualTo("DA_XAC_NHAN");
    assertThat(tasks.status).isEqualTo("CHO_NGHIEM_THU");
    var last = service.acceptVersion(TENANT, v2, 2, REVIEWER);
    assertThat(last.taskRequest()).contains(TaskInterlock.TaskRequest.APPROVE);
    assertThat(tasks.status).isEqualTo("HOAN_THANH");
    assertThat(tasks.calls.get(tasks.calls.size() - 1).completedAt())
        .as("ngày hoàn thành tạm là ngày nộp phiên bản cuối")
        .isEqualTo(Instant.parse("2027-03-12T03:00:00Z"));
  }

  @Test
  void tuChoiDuaNhiemVuVeDangThucHienMaKhongDoiPhienBanKhac() {
    UUID d1 = repo.deliverable(BigDecimal.ONE);
    UUID d2 = repo.deliverable(BigDecimal.ONE);
    UUID v1 = repo.version(d1, EvidenceStatus.DANG_NGHIEM_THU);
    UUID v2 = repo.version(d2, EvidenceStatus.DA_XAC_NHAN);
    tasks.status = "CHO_NGHIEM_THU";

    var r = service.rejectVersion(TENANT, v1, 0, "Không đúng sản phẩm", REVIEWER);

    assertThat(r.version().status()).isEqualTo(EvidenceStatus.TU_CHOI);
    assertThat(r.taskRequest()).contains(TaskInterlock.TaskRequest.REWORK);
    assertThat(tasks.status).isEqualTo("DANG_THUC_HIEN");
    assertThat(tasks.calls.get(0).reason()).isEqualTo("Không đúng sản phẩm");
    assertThat(repo.versions.get(v2).status()).isEqualTo(EvidenceStatus.DA_XAC_NHAN);
    assertThat(repo.versions.get(v2).rowVersion()).isZero();
    // Bản bị từ chối không còn trong biến deliverables của guard.
    assertThat(tasks.calls.get(0).deliverables())
        .extracting(TaskApi.DeliverableState::status)
        .containsExactly("DA_XAC_NHAN");
  }

  @Test
  void lechPhienBanTra409VaKhongGhi() {
    UUID d = repo.deliverable(BigDecimal.ONE);
    UUID v = repo.version(d, EvidenceStatus.NHAP);
    assertThatThrownBy(() -> service.submitVersion(TENANT, v, 5, MEMBER))
        .isInstanceOf(VersionConflictException.class);
    assertThat(repo.versions.get(v).status()).isEqualTo(EvidenceStatus.NHAP);
  }

  @Test
  void viPhamQuyTacTraMaHopDong() {
    UUID d = repo.deliverable(BigDecimal.ONE);
    UUID v = repo.version(d, EvidenceStatus.DA_NOP);
    assertThatThrownBy(() -> service.acceptVersion(TENANT, v, 0, REVIEWER))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("PRODUCT-409-INVALID-STATE"));
    assertThatThrownBy(() -> service.saveDraft(TENANT, v, 0, null, MEMBER))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void kyDaKhoaBiChan() {
    UUID d = repo.deliverable(BigDecimal.ONE);
    UUID v = repo.version(d, EvidenceStatus.NHAP);
    periods.locked = true;
    assertThatThrownBy(() -> service.submitVersion(TENANT, v, 0, MEMBER))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("PRODUCT-409-PERIOD-LOCKED"));
  }

  @Test
  void guardNopChuaDatThiGiuPhienBanDaNop() {
    UUID d = repo.deliverable(BigDecimal.ONE);
    UUID v = repo.version(d, EvidenceStatus.NHAP);
    tasks.failWith = new BusinessException("COMMON-422-VALIDATION", "chưa đạt");
    var r = service.submitVersion(TENANT, v, 0, MEMBER);
    assertThat(r.version().status()).isEqualTo(EvidenceStatus.DA_NOP);
    assertThat(r.taskOutcome()).isEmpty();

    // Lệnh của người nghiệm thu bị MOD-02 từ chối thì cả thao tác minh chứng quay lui.
    UUID v2 = repo.version(d, EvidenceStatus.DANG_NGHIEM_THU);
    tasks.failWith = new BusinessException("COMMON-403-OUT-OF-SCOPE", "sai vai trò");
    assertThatThrownBy(() -> service.requestRevision(TENANT, v2, 0, "Bổ sung", REVIEWER))
        .isInstanceOf(BusinessException.class);
  }

  /** Kho trong bộ nhớ. */
  static final class Repo implements EvidenceLifecycleRepository {
    final Map<UUID, EvidenceVersion> versions = new HashMap<>();
    final Map<UUID, BigDecimal> deliverables = new HashMap<>();
    final Map<UUID, String> deliverableStatus = new HashMap<>();

    UUID deliverable(BigDecimal planned) {
      UUID id = UUID.randomUUID();
      deliverables.put(id, planned);
      return id;
    }

    UUID version(UUID deliverable, EvidenceStatus status) {
      EvidenceVersion v = EvidenceFixtures.version(UUID.randomUUID(), deliverable, TASK, status);
      versions.put(v.id(), v);
      return v.id();
    }

    @Override
    public Optional<LockedVersion> lockVersion(TenantId tenant, UUID versionId) {
      return Optional.ofNullable(versions.get(versionId))
          .map(v -> new LockedVersion(v, PERIOD, 20271));
    }

    @Override
    public Optional<Long> writeVersion(
        TenantId tenant, int bucket, EvidenceVersion v, long expected, UUID actor, Instant at) {
      EvidenceVersion cur = versions.get(v.id());
      if (cur.rowVersion() != expected) {
        return Optional.empty();
      }
      long next = expected + 1;
      versions.put(
          v.id(),
          new EvidenceVersion(
              v.id(),
              v.evidenceId(),
              v.deliverableId(),
              v.taskId(),
              v.versionNo(),
              v.status(),
              v.objectId(),
              v.contentSha256(),
              v.scanStatus(),
              v.aiPrefill(),
              v.userConfirmedAt(),
              v.submittedBy(),
              v.submittedAt(),
              v.acceptedAt(),
              v.note(),
              v.excludedPeriodId(),
              next));
      return Optional.of(next);
    }

    @Override
    public void markEvidenceCurrent(
        TenantId t, int b, UUID evidenceId, UUID versionId, String status, UUID a, Instant at) {}

    @Override
    public List<DeliverableRow> lockDeliverablesOfTask(TenantId tenant, UUID taskId) {
      List<DeliverableRow> rows = new ArrayList<>();
      deliverables.forEach((id, q) -> rows.add(new DeliverableRow(id, 20271, q)));
      rows.sort(Comparator.comparing(DeliverableRow::id));
      return rows;
    }

    @Override
    public List<EvidenceVersion> currentVersionsOfTask(TenantId tenant, UUID taskId) {
      return List.copyOf(versions.values());
    }

    @Override
    public void writeDeliverableRollup(
        TenantId t, int b, UUID id, DeliverableRollup rollup, UUID a, Instant at) {
      deliverableStatus.put(id, rollup.status().name());
    }
  }

  /** {@link TaskApi} giả: áp lệnh theo trạng thái nguồn và ghi lại lời gọi. */
  static final class Tasks implements TaskApi {
    final List<TransitionRequest> calls = new ArrayList<>();
    String status = "DANG_THUC_HIEN";
    BusinessException failWith;

    @Override
    public Optional<TaskSummary> find(TenantId tenant, UUID taskId) {
      return Optional.empty();
    }

    @Override
    public List<TaskSummary> listForPerson(
        TenantId tenant, PeriodId period, UUID personId, UUID afterId, int limit) {
      return List.of();
    }

    @Override
    public List<TaskSummary> listForUnit(
        TenantId tenant, PeriodId period, UUID orgUnitId, UUID afterId, int limit) {
      return List.of();
    }

    @Override
    public TransitionOutcome requestTransition(TenantId tenant, TransitionRequest request) {
      calls.add(request);
      if (failWith != null) {
        throw failWith;
      }
      Outcome outcome = Outcome.NOT_APPLICABLE;
      if (request.command().from().equals(status)) {
        status = request.command().to();
        outcome = Outcome.APPLIED;
      }
      return new TransitionOutcome(outcome, null);
    }
  }

  /** {@link PeriodApi} giả: kỳ mở trừ khi {@link #locked}. */
  static final class Periods implements PeriodApi {
    boolean locked;

    @Override
    public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
      return Optional.empty();
    }

    @Override
    public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId periodId) {
      return Optional.empty();
    }

    @Override
    public void assertOpen(TenantId tenant, PeriodId period) {
      if (locked) {
        throw new PeriodLockedException(period);
      }
    }
  }
}
