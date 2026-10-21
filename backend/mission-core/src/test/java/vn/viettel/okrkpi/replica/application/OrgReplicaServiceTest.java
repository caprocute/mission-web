package vn.viettel.okrkpi.replica.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.replica.api.AssignmentBackdatedEvent;
import vn.viettel.okrkpi.replica.domain.AppliedEvent;
import vn.viettel.okrkpi.replica.domain.AssignmentSnapshot;
import vn.viettel.okrkpi.replica.domain.EntityType;
import vn.viettel.okrkpi.replica.domain.OrgReplicaStore;
import vn.viettel.okrkpi.replica.domain.OrgTreeCycleException;
import vn.viettel.okrkpi.replica.domain.PersonSnapshot;
import vn.viettel.okrkpi.replica.domain.UnitSnapshot;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm thử đơn vị logic áp bản sao tổ chức (CORE-00-03): đổi cấp trên, vòng lặp, đính chính lùi.
 */
class OrgReplicaServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final AppliedEvent EVENT =
      new AppliedEvent(TENANT, UUID.randomUUID(), Instant.parse("2026-10-21T00:00:00Z"));

  private OrgReplicaStore store;
  private PeriodApi periods;
  private final List<Object> published = new ArrayList<>();
  private OrgReplicaService service;

  @BeforeEach
  void setUp() {
    store = mock(OrgReplicaStore.class);
    periods = mock(PeriodApi.class);
    service = new OrgReplicaService(store, periods, published::add);
    when(store.checkpoint(any(), any())).thenReturn(7L);
  }

  @Test
  void donViMoiGanVaoCapTrenCuaCaHaiCay() {
    UUID unit = UUID.randomUUID();
    UUID party = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UnitSnapshot snapshot = unit(unit, party, admin, null);
    when(store.upsertUnit(snapshot, EVENT)).thenReturn(true);
    when(store.closureParent(any(), any())).thenReturn(Optional.empty());

    assertThat(service.applyUnit(snapshot, EVENT)).isEqualTo(7L);

    verify(store).attachSubtree("DANG", unit, party);
    verify(store).attachSubtree("CHUYEN_MON", unit, admin);
    verify(store).checkpoint(EntityType.ORG_UNIT, EVENT);
  }

  @Test
  void capTrenKhongDoiHoacPhienBanCuThiKhongTinhLaiBaoDong() {
    UUID unit = UUID.randomUUID();
    UUID parent = UUID.randomUUID();
    UnitSnapshot same = unit(unit, parent, parent, null);
    when(store.upsertUnit(same, EVENT)).thenReturn(true);
    when(store.closureParent(any(), any())).thenReturn(Optional.of(parent));
    service.applyUnit(same, EVENT);

    UnitSnapshot stale = unit(unit, UUID.randomUUID(), null, null);
    when(store.upsertUnit(stale, EVENT)).thenReturn(false);
    service.applyUnit(stale, EVENT);

    UnitSnapshot closed = unit(unit, UUID.randomUUID(), null, LocalDate.of(2026, 6, 30));
    when(store.upsertUnit(closed, EVENT)).thenReturn(true);
    service.applyUnit(closed, EVENT);

    verify(store, never()).detachSubtree(any(), any());
    verify(store, never()).attachSubtree(any(), any(), any());
  }

  @Test
  void boCapTrenThiTachCayConThanhGoc() {
    UUID unit = UUID.randomUUID();
    UnitSnapshot snapshot = unit(unit, null, null, null);
    when(store.upsertUnit(snapshot, EVENT)).thenReturn(true);
    when(store.closureParent(any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

    service.applyUnit(snapshot, EVENT);

    verify(store).detachSubtree("DANG", unit);
    verify(store).detachSubtree("CHUYEN_MON", unit);
    verify(store, never()).attachSubtree(any(), any(), any());
  }

  @Test
  void capTrenLaHauDueThiBaoVongLap() {
    UUID unit = UUID.randomUUID();
    UUID child = UUID.randomUUID();
    UnitSnapshot snapshot = unit(unit, child, null, null);
    when(store.upsertUnit(snapshot, EVENT)).thenReturn(true);
    when(store.closureParent(any(), any())).thenReturn(Optional.empty());
    when(store.closureContains("DANG", unit, child)).thenReturn(true);

    assertThatThrownBy(() -> service.applyUnit(snapshot, EVENT))
        .isInstanceOf(OrgTreeCycleException.class)
        .hasMessageContaining(unit.toString());
    verify(store, never()).detachSubtree(any(), any());
  }

  @Test
  void phanCongLuiVaoTruocKyHienHanhPhatSuKienKhongKemKy() {
    AssignmentSnapshot a = assignment(LocalDate.of(2025, 12, 1));
    when(store.upsertAssignment(a, EVENT)).thenReturn(true);
    when(periods.findCurrent(TENANT)).thenReturn(Optional.of(period(PeriodStatus.MO)));

    assertThat(service.applyAssignment(a, EVENT)).isEqualTo(7L);

    assertThat(published).singleElement().isInstanceOf(AssignmentBackdatedEvent.class);
    AssignmentBackdatedEvent e = (AssignmentBackdatedEvent) published.getFirst();
    assertThat(e.periodId()).isNull();
    assertThat(e.assignmentId()).isEqualTo(a.id());
    assertThat(a.branch()).isEqualTo("CHINH");
  }

  @Test
  void phanCongTrongKyDaKhoaPhatSuKienKemKy() {
    AssignmentSnapshot a = assignment(LocalDate.of(2026, 3, 1));
    when(store.upsertAssignment(a, EVENT)).thenReturn(true);
    PeriodSnapshot locked = period(PeriodStatus.DA_KHOA);
    when(periods.findCurrent(TENANT)).thenReturn(Optional.of(locked));

    service.applyAssignment(a, EVENT);

    assertThat(((AssignmentBackdatedEvent) published.getFirst()).periodId())
        .isEqualTo(locked.id().value());
  }

  @Test
  void phanCongTrongKyDangMoHoacPhienBanCuKhongPhatSuKien() {
    AssignmentSnapshot open = assignment(LocalDate.of(2026, 3, 1));
    when(store.upsertAssignment(open, EVENT)).thenReturn(true);
    when(periods.findCurrent(TENANT)).thenReturn(Optional.of(period(PeriodStatus.MO)));
    service.applyAssignment(open, EVENT);

    AssignmentSnapshot stale = assignment(LocalDate.of(2025, 1, 1));
    when(store.upsertAssignment(stale, EVENT)).thenReturn(false);
    service.applyAssignment(stale, EVENT);

    assertThat(published).isEmpty();
  }

  @Test
  void nhanSuGhiCheckpoint() {
    PersonSnapshot p = new PersonSnapshot(UUID.randomUUID(), "Nguyễn Văn A", true, "DANG_LAM", 1);
    assertThat(service.applyPerson(p, EVENT)).isEqualTo(7L);
    verify(store).upsertPerson(p, EVENT);
    verify(store).checkpoint(EntityType.PERSON, EVENT);
  }

  private static UnitSnapshot unit(UUID id, UUID party, UUID admin, LocalDate validTo) {
    return new UnitSnapshot(
        id, LocalDate.of(2026, 1, 1), validTo, "DV", "Đơn vị", "CHI_BO", party, admin, null, 1);
  }

  private static AssignmentSnapshot assignment(LocalDate from) {
    return new AssignmentSnapshot(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        "CHINH_THUC",
        true,
        false,
        from,
        null,
        1);
  }

  private static PeriodSnapshot period(PeriodStatus status) {
    return new PeriodSnapshot(
        new PeriodId(UUID.randomUUID()),
        TENANT,
        "2026",
        "Năm 2026",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 31),
        status);
  }
}
