package vn.viettel.okrkpi.oversight.application.reopen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.oversight.application.port.ApproverDirectory;
import vn.viettel.okrkpi.oversight.application.port.ReopenEvents;
import vn.viettel.okrkpi.oversight.application.port.ReopenNotices;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.NotFoundException;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.View;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScopeItem;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenWorkflow;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bốn mắt của yêu cầu mở lại kỳ (CORE-07-08, QT-13 bước 5–8, BR-13-02..04). */
class ReopenApprovalServiceTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2027-01-22T02:00:00Z"), ZoneOffset.UTC);
  private static final String OK = "Đồng ý mở lại trong phạm vi đã nêu.";

  private final UUID unit = UUID.randomUUID();
  private final UUID parent = UUID.randomUUID();
  private final UUID profile = UUID.randomUUID();
  private final UUID periodId = UUID.randomUUID();
  private final UUID requester = UUID.randomUUID();
  private final UUID decider = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();
  private final UUID reviewer = UUID.randomUUID();

  private FakeReopenRepository repo;
  private Directory directory;
  private List<String> notices;
  private List<List<UUID>> reopened;
  private List<String> audits;
  private PeriodStatus periodStatus;
  private int signs;
  private ReopenApprovalService service;

  @BeforeEach
  void setUp() {
    repo = new FakeReopenRepository();
    directory = new Directory();
    directory.hold(ReopenWorkflow.DECIDER, unit, requester, reviewer, decider);
    directory.hold(ReopenWorkflow.SECOND_APPROVER, unit, decider, second);
    directory.participants.add(reviewer);
    notices = new ArrayList<>();
    reopened = new ArrayList<>();
    audits = new ArrayList<>();
    periodStatus = PeriodStatus.DA_KHOA;
    service = service(true);
  }

  private ReopenApprovalService service(boolean withSigner) {
    ReopenNotices n =
        new ReopenNotices() {
          @Override
          public void approvalRequested(TenantId t, UUID assignee, ReopenRequest r, int round) {
            notices.add("can-duyet:" + round + ":" + assignee);
          }

          @Override
          public void decided(TenantId t, ReopenRequest r) {
            notices.add("ket-qua:" + r.status());
          }
        };
    ReopenEvents e = (t, r, approvers, at) -> reopened.add(approvers);
    return new ReopenApprovalService(
        repo,
        directory,
        withSigner
            ? (t, id, key, auth) -> {
              signs++;
              return UUID.randomUUID();
            }
            : null,
        n,
        e,
        (t, actor, action, type, id, purpose, role) -> audits.add(action + ":" + role),
        new Periods(),
        CLOCK);
  }

  private ReopenRequest request(ReopenScope scope, List<UUID> ids, List<UUID> units) {
    return new ReopenRequest(
        UUID.randomUUID(),
        periodId,
        20264,
        "MLK-2027-001",
        scope,
        ids,
        List.of(new ReopenScopeItem("appraisal.score", profile, unit, null, List.of("diem"))),
        units,
        new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ 12"),
        "KE_HOACH",
        "Lý do",
        requester,
        2,
        ReopenStatus.CHO_DUYET_1,
        3,
        CLOCK.instant(),
        null,
        null,
        null,
        ReopenWorkflow.empty(unit),
        CLOCK.instant(),
        0);
  }

  private ReopenRequest stored() {
    ReopenRequest r =
        service.assign(T, request(ReopenScope.HO_SO, List.of(profile), List.of(unit)));
    repo.rows.put(r.id(), r);
    notices.clear();
    return r;
  }

  private Actor actor(UUID id) {
    return new Actor(
        id,
        List.of(ReopenWorkflow.DECIDER, ReopenWorkflow.SECOND_APPROVER),
        unit,
        "aal2",
        CLOCK.instant().getEpochSecond() - 60);
  }

  private static String etag(ReopenRequest r) {
    return "\"" + r.rowVersion() + "\"";
  }

  private ReopenRequest current(UUID id) {
    return repo.rows.get(id);
  }

  private View approve(UUID who, ReopenRequest r) {
    return service.decide(T, actor(who), r.id(), etag(current(r.id())), true, OK, "k", null);
  }

  private boolean sign(ReopenRequest r) {
    ReopenRequest now = current(r.id());
    return service.onSigned(T, r.id(), now.workflow().pending().signRequestId(), CLOCK.instant());
  }

  @Test
  void chonNguoiDuyetBoQuaNguoiLapVaNguoiDaThamDinh() {
    ReopenRequest r = stored();
    assertThat(r.workflow().assignee(1)).isEqualTo(decider);
    // decider đã ở lượt 1 nên lượt 2 lấy người kế tiếp.
    assertThat(r.workflow().assignee(2)).isEqualTo(second);
    assertThat(directory.lastProfiles).containsExactly(profile);
  }

  @Test
  void donViChiCoMotNguoiThiNangLenCapTren() {
    directory.holders.clear();
    directory.hold(ReopenWorkflow.DECIDER, unit, decider);
    directory.hold(ReopenWorkflow.SECOND_APPROVER, unit, decider);
    directory.hold(ReopenWorkflow.SECOND_APPROVER, parent, second);
    directory.ancestors.put(unit, List.of(parent));
    ReopenRequest r = service.assign(T, request(ReopenScope.DON_VI, List.of(unit), List.of(unit)));
    assertThat(r.workflow().assignee(1)).isEqualTo(decider);
    assertThat(r.workflow().assignee(2)).isEqualTo(second);
    assertThat(notices).containsExactly("can-duyet:1:" + decider);
    assertThat(directory.lastUnits).containsExactly(unit);
  }

  @Test
  void khongCoNguoiPhuHopThiDeTrongKhongHaXuongMotNguoi() {
    directory.holders.clear();
    directory.hold(ReopenWorkflow.DECIDER, unit, requester);
    ReopenRequest r = service.assign(T, request(ReopenScope.TOAN_KY, List.of(), List.of()));
    assertThat(r.workflow().assignee(1)).isNull();
    assertThat(r.workflow().assignee(2)).isNull();
    assertThat(r.workflow().slots()).hasSize(2);
    assertThat(notices).isEmpty();
    ReopenRequest none =
        new ReopenRequest(
            r.id(),
            r.periodId(),
            r.periodBucket(),
            r.code(),
            r.scope(),
            r.scopeIds(),
            r.items(),
            List.of(),
            r.basis(),
            r.auditKind(),
            r.reason(),
            r.requestedBy(),
            2,
            r.status(),
            3,
            r.openUntil(),
            null,
            null,
            null,
            ReopenWorkflow.empty(null),
            r.createdAt(),
            0);
    assertThat(service.assign(T, none).workflow().assignee(1)).isNull();
  }

  @Test
  void duHaiPheDuyetThiDaDuyetVaPhatMotEvt22() {
    ReopenRequest r = stored();
    View v = approve(decider, r);
    assertThat(v.request().status()).isEqualTo(ReopenStatus.CHO_DUYET_1);
    assertThat(v.request().workflow().pending().approverId()).isEqualTo(decider);
    assertThat(v.request().rowVersion()).isEqualTo(1);
    // Gửi lại khi đang chờ ký trả trạng thái hiện tại, không mở thêm yêu cầu ký.
    approve(decider, r);
    assertThat(signs).isEqualTo(1);

    assertThat(sign(r)).isTrue();
    assertThat(current(r.id()).status()).isEqualTo(ReopenStatus.CHO_DUYET_2);
    assertThat(repo.approvals.get(r.id())).hasSize(1);
    assertThat(notices).containsExactly("can-duyet:2:" + second);

    approve(second, r);
    UUID signRequest = current(r.id()).workflow().pending().signRequestId();
    assertThat(sign(r)).isTrue();
    assertThat(current(r.id()).status()).isEqualTo(ReopenStatus.DA_DUYET);
    assertThat(reopened).containsExactly(List.of(decider, second));
    assertThat(notices).endsWith("ket-qua:DA_DUYET");
    assertThat(audits)
        .containsExactly("PHE_DUYET_MO_LAI_KY:decider", "PHE_DUYET_MO_LAI_KY:second_approver");
    // EVT-18 đến lại: không ghi thêm, không phát EVT-22 lần nữa.
    assertThat(service.onSigned(T, r.id(), signRequest, null)).isFalse();
    assertThat(reopened).hasSize(1);

    assertThat(service.confirmExecuted(T)).isZero();
    periodStatus = PeriodStatus.MO_LAI_HAU_KIEM;
    assertThat(service.confirmExecuted(T)).isEqualTo(1);
    assertThat(current(r.id()).status()).isEqualTo(ReopenStatus.DA_THUC_HIEN);
    assertThat(current(r.id()).executedAt()).isEqualTo(CLOCK.instant());
  }

  @Test
  void nguoiDuyetLuot1KhongDuyetTiepLuot2() {
    ReopenRequest r = stored();
    approve(decider, r);
    sign(r);
    assertThat(code(() -> approve(decider, r))).isEqualTo(ReopenApprovalService.FOUR_EYES);
  }

  @Test
  void bonMatTuChoiNguoiLapNguoiThamDinhVaNguoiKhongDuocChiDinh() {
    ReopenRequest r = stored();
    assertThat(code(() -> approve(requester, r))).isEqualTo(ReopenApprovalService.FOUR_EYES);
    assertThat(code(() -> approve(reviewer, r))).isEqualTo(ReopenApprovalService.FOUR_EYES);
    assertThat(code(() -> approve(second, r))).isEqualTo(ReopenApprovalService.FOUR_EYES);
    // Người được chỉ định nhưng đã mất vai trò.
    directory.holders.clear();
    assertThat(code(() -> approve(decider, r))).isEqualTo(ReopenApprovalService.FOUR_EYES);
    assertThat(signs).isZero();
  }

  @Test
  void canBuocLenTrongMuoiLamPhut() {
    ReopenRequest r = stored();
    Actor aal1 = new Actor(decider, List.of(), unit, "aal1", null);
    assertThatThrownBy(() -> service.decide(T, aal1, r.id(), "\"0\"", true, OK, "k", null))
        .isInstanceOf(StepUpRequiredException.class);
    Actor stale =
        new Actor(decider, List.of(), unit, "aal2", CLOCK.instant().getEpochSecond() - 901);
    assertThatThrownBy(() -> service.decide(T, stale, r.id(), "\"0\"", false, OK, "k", null))
        .isInstanceOf(StepUpRequiredException.class);
  }

  @Test
  void kiemTraPhienBanYKienVaTrangThai() {
    ReopenRequest r = stored();
    assertThatThrownBy(() -> service.decide(T, actor(decider), r.id(), null, true, OK, "k", null))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThat(
            code(() -> service.decide(T, actor(decider), r.id(), "\"0\"", true, "Ngắn", "k", null)))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () ->
                service.decide(
                    T,
                    new Actor(UUID.randomUUID(), List.of(), unit, "aal2", null),
                    r.id(),
                    "\"0\"",
                    true,
                    OK,
                    "k",
                    null))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                service.decide(T, actor(decider), UUID.randomUUID(), "\"0\"", true, OK, "k", null))
        .isInstanceOf(NotFoundException.class);
    repo.rows.put(r.id(), r.cancelled("Đã huỷ yêu cầu", 0));
    assertThat(code(() -> approve(decider, r))).isEqualTo("OVERSIGHT-409-INVALID-STATE");
  }

  @Test
  void quyetDinhCuaNguoiKhacDangChoKyThi409() {
    ReopenRequest r = stored();
    approve(decider, r);
    ReopenRequest now = current(r.id());
    ReopenWorkflow.Pending p = now.workflow().pending();
    repo.rows.put(
        r.id(),
        now.with(
            now.status(),
            null,
            null,
            now.workflow()
                .withPending(
                    new ReopenWorkflow.Pending(
                        2,
                        p.approverId(),
                        p.role(),
                        p.decision(),
                        p.comment(),
                        p.acr(),
                        p.signRequestId(),
                        p.requestedAt())),
            now.rowVersion()));
    assertThat(code(() -> approve(decider, r))).isEqualTo("OVERSIGHT-409-INVALID-STATE");
  }

  @Test
  void chuaCauHinhKySoThi503() {
    service = service(false);
    ReopenRequest r = stored();
    assertThatThrownBy(() -> approve(decider, r))
        .isInstanceOf(ReopenApprovalService.SigningUnavailableException.class);
  }

  @Test
  void tuChoiThiDongYeuCauVaBaoNguoiLap() {
    ReopenRequest r = stored();
    service.decide(
        T, actor(decider), r.id(), "\"0\"", false, "Căn cứ chưa đủ để mở lại.", "k", null);
    assertThat(service.signSource(T, r.id())).get().extracting(s -> s.signer()).isEqualTo(decider);
    assertThat(service.onSigned(T, r.id(), UUID.randomUUID(), null)).isFalse();
    assertThat(service.onSigned(T, UUID.randomUUID(), UUID.randomUUID(), null)).isFalse();
    assertThat(sign(r)).isTrue();
    assertThat(current(r.id()).status()).isEqualTo(ReopenStatus.TU_CHOI);
    assertThat(notices).containsExactly("ket-qua:TU_CHOI");
    assertThat(audits).containsExactly("TU_CHOI_MO_LAI_KY:decider");
    assertThat(service.signSource(T, r.id())).isEmpty();
    assertThat(reopened).isEmpty();
  }

  @Test
  void chuaChonDuocNguoiLucLapThiChonLaiKhiDuyet() {
    directory.holders.clear();
    ReopenRequest r = stored();
    assertThat(r.workflow().assignee(1)).isNull();
    directory.hold(ReopenWorkflow.DECIDER, unit, decider);
    View v = approve(decider, r);
    assertThat(v.request().workflow().assignee(1)).isEqualTo(decider);
  }

  @Test
  void luot2ChuaDuSoPheDuyetThiGiuChoDuyet2() {
    ReopenRequest r = stored();
    approve(decider, r);
    sign(r);
    // Lượt 1 bị ghi là từ chối ở dữ liệu cũ: chỉ một DONG_Y thì chưa đủ.
    repo.approvals.get(r.id()).clear();
    approve(second, r);
    sign(r);
    assertThat(current(r.id()).status()).isEqualTo(ReopenStatus.CHO_DUYET_2);
    assertThat(reopened).isEmpty();
  }

  private static String code(Runnable action) {
    try {
      action.run();
    } catch (BusinessException e) {
      return e.errorCode().code();
    }
    throw new AssertionError("không ném BusinessException");
  }

  private final class Directory implements ApproverDirectory {
    final Map<String, List<UUID>> holders = new HashMap<>();
    final Map<UUID, List<UUID>> ancestors = new HashMap<>();
    final Set<UUID> participants = new HashSet<>();
    Collection<UUID> lastProfiles;
    Collection<UUID> lastUnits;

    void hold(String role, UUID orgUnit, UUID... people) {
      holders.computeIfAbsent(role + "@" + orgUnit, k -> new ArrayList<>()).addAll(List.of(people));
    }

    @Override
    public List<UUID> holders(TenantId tenant, String roleCode, UUID orgUnitId, Instant at) {
      return holders.getOrDefault(roleCode + "@" + orgUnitId, List.of());
    }

    @Override
    public List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId) {
      return ancestors.getOrDefault(orgUnitId, List.of());
    }

    @Override
    public Set<UUID> participants(
        TenantId tenant, UUID period, Collection<UUID> profileIds, Collection<UUID> unitIds) {
      lastProfiles = profileIds;
      lastUnits = unitIds;
      return participants;
    }
  }

  private final class Periods implements PeriodApi {
    @Override
    public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
      return Optional.empty();
    }

    @Override
    public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId id) {
      return Optional.of(
          new PeriodSnapshot(
              id,
              tenantId,
              "2026-Q4",
              "Quý IV/2026",
              LocalDate.of(2026, 10, 1),
              LocalDate.of(2026, 12, 31),
              periodStatus));
    }

    @Override
    public void assertOpen(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {}
  }
}
