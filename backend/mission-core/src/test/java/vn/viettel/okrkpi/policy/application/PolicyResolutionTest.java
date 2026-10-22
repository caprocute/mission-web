package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.BindingRow;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.PackVersionRow;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thứ tự khớp dòng gắn và thuật toán chọn theo khoảng hiệu lực (CORE-05-03, X-PP-10). */
class PolicyResolutionTest {

  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID GROUP = UUID.randomUUID();

  private static PackVersionRow row(String version, String tuKy, String denKy) {
    return new PackVersionRow(
        UUID.randomUUID(),
        "goi",
        "DANG",
        UUID.randomUUID(),
        version,
        PolicyVersionStatus.DA_PHAT_HANH,
        null,
        tuKy,
        denKy,
        "ab",
        null);
  }

  private static BindingRow binding(String flow, UUID group, UUID unit) {
    return new BindingRow(UUID.randomUUID(), flow, group, unit, false, row("1.0.0", null, null));
  }

  @Test
  void dongGanCuTheNhatThang() {
    BindingRow all = binding("DANG", null, null);
    BindingRow both = binding("CA_HAI", null, null);
    BindingRow group = binding("DANG", GROUP, null);
    BindingRow unit = binding("CA_HAI", null, UNIT);
    BindingRow otherUnit = binding("DANG", null, UUID.randomUUID());
    List<BindingRow> rows = List.of(all, both, group, unit, otherUnit);

    assertThat(PolicyResolution.mostSpecific(rows, "DANG", GROUP, UNIT)).contains(unit);
    assertThat(PolicyResolution.mostSpecific(rows, "DANG", GROUP, null)).contains(group);
    assertThat(PolicyResolution.mostSpecific(rows, "DANG", null, null)).contains(all);
    assertThat(PolicyResolution.mostSpecific(List.of(both), "NHA_NUOC", null, null)).contains(both);
    assertThat(PolicyResolution.mostSpecific(List.of(otherUnit), "DANG", null, UNIT)).isEmpty();
  }

  @Test
  void chonTuKyLonNhatRoiPhienBanLonNhat() {
    PackVersionRow v1 = row("1.0.0", "2026-Q1", null);
    PackVersionRow v2 = row("2.0.0", "2027-Q1", null);
    PackVersionRow v21 = row("2.1.0", "2027-Q1", null);
    PackVersionRow closed = row("9.0.0", "2025-Q1", "2025-Q4");
    PackVersionRow broken = row("9.9.9", "quy-1", null);
    List<PackVersionRow> all = List.of(v1, v21, v2, closed, broken);

    assertThat(PolicyResolution.effective(all, LocalDate.of(2026, 10, 1))).contains(v1);
    assertThat(PolicyResolution.effective(all, LocalDate.of(2027, 4, 1))).contains(v21);
    assertThat(PolicyResolution.effective(all, LocalDate.of(2025, 4, 1))).contains(closed);
    assertThat(PolicyResolution.effective(all, LocalDate.of(2024, 4, 1))).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void kyKhongTonTaiHoacKhongCoGoiThiKhongGhim() {
    PolicyResolutionRepository repo = mock(PolicyResolutionRepository.class);
    PeriodApi periods = mock(PeriodApi.class);
    ObjectProvider<PeriodApi> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(periods);
    PolicyResolution resolution =
        new PolicyResolution(repo, mock(PolicyPackRepository.class), provider, new ObjectMapper());
    TenantId tenant = TenantId.of(UUID.randomUUID());
    PeriodId period = PeriodId.of(UUID.randomUUID());
    when(repo.bindings(any(), any(), anyString())).thenReturn(List.of());
    when(periods.findById(tenant, period)).thenReturn(Optional.empty());

    assertThat(resolution.resolve(tenant, period, "DANG", null, null)).isEmpty();
    assertThat(resolution.pinForPeriod(tenant, period, "CA_HAI", LocalDate.of(2026, 10, 1)))
        .isZero();
    verify(repo, never()).pin(any(), any(), any(), anyString(), any(), any());
    verify(repo).releasedVersions(tenant, "DANG");
    verify(repo).releasedVersions(eq(tenant), eq("NHA_NUOC"));
  }
}
