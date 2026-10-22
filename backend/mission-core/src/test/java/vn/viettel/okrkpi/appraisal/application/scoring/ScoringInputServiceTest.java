package vn.viettel.okrkpi.appraisal.application.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class ScoringInputServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final FakeRepository repo = new FakeRepository();
  private final ScoringInputService service = new ScoringInputService(repo, Clock.fixed(NOW));

  @Test
  void trangChuyenNguyenThamSoVaChanGioiHanSai() {
    UUID after = UUID.randomUUID();
    assertThat(service.page(tenant, period, after, 201)).isEmpty();
    assertThat(repo.lastAfter).isEqualTo(after);
    assertThat(repo.lastLimit).isEqualTo(201);
    assertThatThrownBy(() -> service.page(tenant, period, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.page(tenant, period, null, 202))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nhiemVuKetThucDanhDauMotLanChoMoiSuKien() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID event = ScoringInputService.eventKey(UUID.randomUUID(), "HOAN_THANH", NOW);
    List<UUID> assignees = new ArrayList<>(List.of(a, b, a));
    assignees.add(null);
    assertThat(service.forAssignees(tenant, period, event, assignees)).isEqualTo(2);
    assertThat(repo.marked).containsExactly(a, b);
    assertThat(repo.markedAt).isEqualTo(NOW);
    assertThat(service.forAssignees(tenant, period, event, List.of(a))).isZero();
    assertThat(repo.claims).isEqualTo(1);
  }

  @Test
  void khongCoNguoiDuocGiaoHoacKyThiBoQua() {
    UUID event = UUID.randomUUID();
    assertThat(service.forAssignees(tenant, period, event, List.of())).isZero();
    assertThat(service.forAssignees(tenant, period, event, null)).isZero();
    assertThat(service.forAssignees(tenant, null, event, List.of(UUID.randomUUID()))).isZero();
    assertThat(repo.claims).isZero();
  }

  @Test
  void khoaSuKienOnDinhTheoNhiemVuTrangThaiThoiDiem() {
    UUID task = UUID.randomUUID();
    assertThat(ScoringInputService.eventKey(task, "HUY", NOW))
        .isEqualTo(ScoringInputService.eventKey(task, "HUY", NOW))
        .isNotEqualTo(ScoringInputService.eventKey(task, "HOAN_THANH", NOW))
        .isNotEqualTo(ScoringInputService.eventKey(task, "HUY", null));
  }

  @Test
  void dauVaoThieuTieuChiThanhDanhSachRong() {
    ScoringInputRepository.ScoringInput in =
        new ScoringInputRepository.ScoringInput(
            UUID.randomUUID(), null, "DANG", null, null, false, false, null, null);
    assertThat(in.generalCriteria()).isEmpty();
  }

  private static final class FakeRepository implements ScoringInputRepository {
    private final Set<UUID> seen = new HashSet<>();
    private UUID lastAfter;
    private int lastLimit;
    private int claims;
    private List<UUID> marked = List.of();
    private Instant markedAt;

    @Override
    public List<ScoringInput> page(TenantId tenant, UUID periodId, UUID after, int limit) {
      lastAfter = after;
      lastLimit = limit;
      return List.of();
    }

    @Override
    public boolean claim(TenantId tenant, String consumer, UUID eventId, String source) {
      boolean added = seen.add(eventId);
      if (added) {
        claims++;
      }
      return added;
    }

    @Override
    public int markChanged(TenantId tenant, UUID periodId, Collection<UUID> personIds, Instant at) {
      marked = List.copyOf(personIds);
      markedAt = at;
      return personIds.size();
    }
  }
}
