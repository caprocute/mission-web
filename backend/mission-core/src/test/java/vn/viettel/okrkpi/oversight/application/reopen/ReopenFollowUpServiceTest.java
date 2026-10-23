package vn.viettel.okrkpi.oversight.application.reopen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.oversight.application.port.ReopenForms.FormRef;
import vn.viettel.okrkpi.oversight.application.port.ReopenNotices;
import vn.viettel.okrkpi.oversight.application.port.ReopenProgressRepository;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenFollowUp;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenProgress;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScopeItem;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenWorkflow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Điều phối tính lại, sinh lại, ký lại sau EVT-22 (CORE-07-09). */
class ReopenFollowUpServiceTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());
  private static final Instant AT = Instant.parse("2027-04-10T02:00:00Z");
  private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);

  private final UUID profile = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final UUID requester = UUID.randomUUID();
  private final UUID mau2 = UUID.randomUUID();
  private final UUID mau3 = UUID.randomUUID();
  private final FakeReopenRepository repo = new FakeReopenRepository();
  private final FakeProgress progress = new FakeProgress();
  private final Map<UUID, FormRef> forms = new HashMap<>();
  private final List<String> audits = new ArrayList<>();
  private final List<String> notices = new ArrayList<>();
  private final List<UUID> relocks = new ArrayList<>();
  private Set<UUID> scope;
  private ReopenFollowUpService service;

  @BeforeEach
  void setUp() {
    scope = Set.of(profile);
    forms.put(mau2, new FormRef(profile, "MAU_2"));
    forms.put(mau3, new FormRef(profile, "MAU_3"));
    service = service(true);
  }

  private ReopenFollowUpService service(boolean withRelock) {
    return new ReopenFollowUpService(
        repo,
        progress,
        (t, r) -> scope,
        (t, id) -> Optional.ofNullable(forms.get(id)),
        withRelock
            ? (t, id, actor) -> {
              relocks.add(id);
              return true;
            }
            : null,
        null,
        new ReopenNotices() {
          @Override
          public void approvalRequested(TenantId t, UUID a, ReopenRequest r, int round) {}

          @Override
          public void decided(TenantId t, ReopenRequest r) {}

          @Override
          public void followUpIncomplete(TenantId t, ReopenRequest r, int pending) {
            notices.add("CHUA_HOAN_TAT:" + pending);
          }

          @Override
          public void followUpClosed(TenantId t, ReopenRequest r, UUID recipient) {
            notices.add("DA_DONG:" + recipient);
          }
        },
        (t, actor, action, type, id, purpose, role) -> audits.add(action),
        CLOCK,
        Set.of("MAU_2", "MAU_3"));
  }

  @Test
  void duTinhLaiSinhLaiKyLaiThiDeNghiKhoaLaiRoiDongKhiNhanEvt09() {
    ReopenRequest r = executed(ReopenScope.HO_SO, List.of(profile));
    service.onRecomputed(T, profile, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT.plusSeconds(60));
    service.onFormGenerated(T, mau2, "MAU_2", AT.plusSeconds(120));
    service.onFormGenerated(T, mau3, "MAU_3", AT.plusSeconds(120));
    service.onSigned(T, "dossier", mau2, AT.plusSeconds(180));
    assertThat(relocks).isEmpty();
    service.onSigned(T, "dossier", mau3, AT.plusSeconds(200));
    assertThat(relocks).containsExactly(r.id());
    assertThat(progress.followUps.get(r.id()).relockRequestedAt()).isEqualTo(AT);

    // Báo lại lần nữa không đề nghị khoá lại lần hai.
    service.onSigned(T, "dossier", mau3, AT.plusSeconds(200));
    assertThat(relocks).hasSize(1);

    service.onRelocked(T, r.id(), AT.plusSeconds(300));
    ReopenRequest closed = repo.rows.get(r.id());
    assertThat(closed.status()).isEqualTo(ReopenStatus.DA_DONG);
    assertThat(closed.relockedAt()).isEqualTo(AT.plusSeconds(300));
    assertThat(notices).containsExactly("DA_DONG:" + requester);
    assertThat(audits)
        .containsExactly("THEO_DOI_HAU_KIEM", "HOAN_TAT_HAU_KIEM", "DONG_YEU_CAU_MO_LAI");

    // EVT-09 trùng hoặc của yêu cầu đã đóng không đổi gì.
    service.onRelocked(T, r.id(), AT.plusSeconds(400));
    assertThat(repo.rows.get(r.id()).relockedAt()).isEqualTo(AT.plusSeconds(300));
  }

  @Test
  void suKienSaiThuTuVanChoCungKetQua() {
    ReopenRequest r = executed(ReopenScope.HO_SO, List.of(profile));
    service.onSigned(T, "dossier", mau3, AT.plusSeconds(200));
    service.onSigned(T, "dossier", mau2, AT.plusSeconds(180));
    service.onFormGenerated(T, mau3, "MAU_3", AT.plusSeconds(120));
    service.onFormGenerated(T, mau2, "MAU_2", AT.plusSeconds(120));
    assertThat(relocks).isEmpty();
    service.onRecomputed(T, profile, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT.plusSeconds(60));
    assertThat(relocks).containsExactly(r.id());
    assertThat(progress.rows.get(r.id()).get(profile).completedAt()).isEqualTo(AT);
  }

  @Test
  void bieuMauSinhTruocLanTinhLaiMoiNhatChuaDuocTinh() {
    executed(ReopenScope.HO_SO, List.of(profile));
    service.onRecomputed(T, profile, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT.plusSeconds(60));
    service.onFormGenerated(T, mau2, "MAU_2", AT.plusSeconds(120));
    service.onFormGenerated(T, mau3, "MAU_3", AT.plusSeconds(120));
    service.onSigned(T, "dossier", mau2, AT.plusSeconds(180));
    // Sửa tiếp nên tính lại lần nữa sau khi đã sinh biểu mẫu.
    service.onRecomputed(T, profile, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT.plusSeconds(190));
    service.onSigned(T, "dossier", mau3, AT.plusSeconds(200));
    assertThat(relocks).isEmpty();
    // Lần tính cũ đến muộn không ghi đè lần mới.
    service.onRecomputed(T, profile, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT.plusSeconds(60));
    assertThat(progress.rows.values().iterator().next().get(profile).recomputedAt())
        .isEqualTo(AT.plusSeconds(190));
  }

  @Test
  void boQuaSuKienKhongThuocHauKiem() {
    ReopenRequest r = executed(ReopenScope.HO_SO, List.of(profile));
    service.onRecomputed(T, profile, UUID.randomUUID(), "TAM_TINH", AT);
    service.onRecomputed(T, null, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT);
    service.onRecomputed(T, other, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT);
    service.onFormGenerated(T, mau2, "MAU_1A", AT);
    service.onFormGenerated(T, UUID.randomUUID(), "MAU_2", AT);
    service.onSigned(T, "appraisal", mau2, AT);
    service.onSigned(T, "dossier", UUID.randomUUID(), AT);
    service.onRelocked(T, null, AT);
    service.onRelocked(T, UUID.randomUUID(), AT);
    assertThat(progress.rows.get(r.id()).get(profile)).isEqualTo(ReopenProgress.pending(profile));
    assertThat(repo.rows.get(r.id()).status()).isEqualTo(ReopenStatus.DA_THUC_HIEN);
  }

  @Test
  void hetHanGiuaChungDanhDauChuaHoanTatRoiDong() {
    scope = Set.of(profile, other);
    ReopenRequest r = executed(ReopenScope.DON_VI, List.of(UUID.randomUUID()));
    service.onRecomputed(T, profile, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT.plusSeconds(60));
    service.onExpired(T, r.id());
    service.onExpired(T, r.id());
    ReopenFollowUp f = progress.followUps.get(r.id());
    assertThat(f.incomplete()).isTrue();
    assertThat(f.pendingProfiles()).isEqualTo(2);
    assertThat(notices).containsExactly("CHUA_HOAN_TAT:2");

    service.onRelocked(T, r.id(), AT.minusSeconds(10));
    ReopenRequest closed = repo.rows.get(r.id());
    assertThat(closed.status()).isEqualTo(ReopenStatus.DA_DONG);
    // Không khoá lại trước thời điểm thực hiện.
    assertThat(closed.relockedAt()).isEqualTo(closed.executedAt());
    assertThat(notices).containsExactly("CHUA_HOAN_TAT:2", "DA_DONG:" + requester);
    assertThat(relocks).isEmpty();
  }

  @Test
  void khoaLaiKhiChuaXongCungDanhDauVaKhongCoHoSoThiKhongKhoaSom() {
    scope = Set.of();
    ReopenRequest r = executed(ReopenScope.TOAN_KY, List.of());
    service.onRecomputed(T, profile, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT);
    assertThat(relocks).isEmpty();
    service.onRelocked(T, r.id(), AT.plusSeconds(10));
    assertThat(progress.followUps.get(r.id()).incomplete()).isTrue();
    assertThat(notices).containsExactly("CHUA_HOAN_TAT:0", "DA_DONG:" + requester);
  }

  @Test
  void khongCoCongKhoaLaiThiChiGhiHoanTat() {
    service = service(false);
    ReopenRequest r = executed(ReopenScope.HO_SO, List.of(profile));
    service.onRecomputed(T, profile, UUID.randomUUID(), "TINH_LAI_HAU_KIEM", AT);
    service.onFormGenerated(T, mau2, "MAU_2", AT.plusSeconds(1));
    service.onFormGenerated(T, mau3, "MAU_3", AT.plusSeconds(1));
    service.onSigned(T, "dossier", mau2, AT.plusSeconds(2));
    service.onSigned(T, "dossier", mau3, AT.plusSeconds(2));
    assertThat(progress.followUps.get(r.id()).relockRequestedAt()).isEqualTo(AT);
    assertThat(audits).contains("HOAN_TAT_HAU_KIEM");
  }

  @Test
  void yeuCauChuaThucHienKhongDuocLapDanhSach() {
    ReopenRequest r = executed(ReopenScope.HO_SO, List.of(profile));
    progress.rows.clear();
    repo.rows.put(
        r.id(), r.with(ReopenStatus.DA_DUYET, null, null, r.workflow(), r.rowVersion() + 1));
    progress.pendingSeed.add(r.id());
    assertThat(service.seedExecuted(T)).isZero();
    assertThat(progress.rows).isEmpty();
  }

  private ReopenRequest executed(ReopenScope s, List<UUID> ids) {
    ReopenRequest r =
        new ReopenRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            20271,
            "MLK-2027-009",
            s,
            ids,
            List.of(new ReopenScopeItem("appraisal.score", profile, null, null, List.of("diem"))),
            List.of(),
            new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, "QĐ 12"),
            "DOT_XUAT",
            "Lý do",
            requester,
            2,
            ReopenStatus.DA_THUC_HIEN,
            10,
            AT.plusSeconds(86_400),
            AT,
            null,
            null,
            ReopenWorkflow.empty(null),
            AT,
            3);
    repo.rows.put(r.id(), r);
    progress.pendingSeed.add(r.id());
    assertThat(service.seedExecuted(T)).isEqualTo(1);
    assertThat(service.seedExecuted(T)).isZero();
    return r;
  }

  /** Kho tiến độ trong bộ nhớ. */
  static final class FakeProgress implements ReopenProgressRepository {
    final List<UUID> pendingSeed = new ArrayList<>();
    final Map<UUID, Map<UUID, ReopenProgress>> rows = new LinkedHashMap<>();
    final Map<UUID, ReopenFollowUp> followUps = new HashMap<>();

    @Override
    public List<UUID> executedNotSeeded(TenantId tenant, int limit) {
      return List.copyOf(pendingSeed);
    }

    @Override
    public void seed(TenantId tenant, ReopenRequest r, Collection<UUID> ids, UUID actor) {
      pendingSeed.remove(r.id());
      Map<UUID, ReopenProgress> m = rows.computeIfAbsent(r.id(), k -> new LinkedHashMap<>());
      ids.forEach(id -> m.putIfAbsent(id, ReopenProgress.pending(id)));
    }

    @Override
    public List<ReopenProgress> of(TenantId tenant, ReopenRequest r) {
      return List.copyOf(rows.getOrDefault(r.id(), Map.of()).values());
    }

    @Override
    public List<UUID> openRequestsOf(TenantId tenant, UUID profileId) {
      return rows.entrySet().stream()
          .filter(e -> e.getValue().containsKey(profileId))
          .map(Map.Entry::getKey)
          .toList();
    }

    @Override
    public void save(TenantId tenant, ReopenRequest r, ReopenProgress p, UUID actor) {
      rows.get(r.id()).put(p.profileId(), p);
    }

    @Override
    public ReopenFollowUp followUp(TenantId tenant, ReopenRequest r) {
      return followUps.getOrDefault(r.id(), ReopenFollowUp.NONE);
    }

    @Override
    public void saveFollowUp(TenantId tenant, ReopenRequest r, ReopenFollowUp f, UUID actor) {
      followUps.put(r.id(), f);
    }
  }
}
