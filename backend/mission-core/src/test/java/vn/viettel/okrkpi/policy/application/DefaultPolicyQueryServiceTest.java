package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.PolicyRulesView;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPolicyPackView;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.TaskTransitionView;
import vn.viettel.okrkpi.policy.application.PolicyResolution.VersionContent;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bộ đệm của {@code PolicyQueryApi} (CORE-05-03): trúng đệm, vô hiệu và vi đo p95 < 1 ms. */
class DefaultPolicyQueryServiceTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final PeriodId period = PeriodId.of(UUID.randomUUID());
  private final UUID versionId = UUID.randomUUID();
  private final PolicyResolution resolution = mock(PolicyResolution.class);
  private final DefaultPolicyQueryService service =
      new DefaultPolicyQueryService(mock(PeriodApi.class), resolution);
  private final ResolvedPolicyPackView view =
      new ResolvedPolicyPackView(
          UUID.randomUUID(), "goi", versionId, "1.0.0", "ab", null, UUID.randomUUID(), false);

  private VersionContent content() {
    return new VersionContent(
        null,
        Map.of("tru_tien_do", 0.05),
        List.of(
            new TaskTransitionView("NHAP", "ASSIGN", "DA_GIAO", List.of("manager"), null, false)),
        new PolicyRulesView(versionId, List.of(), List.of()),
        Map.of(),
        Map.of());
  }

  @Test
  void trungDemKhongGoiLaiKhoVaVoHieuKhiDoiTrangThai() {
    when(resolution.resolve(tenant, period, "DANG", null, null)).thenReturn(Optional.of(view));
    assertThat(service.resolve(tenant, period, "DANG", null, null)).contains(view);
    assertThat(service.resolve(tenant, period, "DANG", null, null)).contains(view);
    verify(resolution, times(1)).resolve(tenant, period, "DANG", null, null);

    service.onStatusChanged(
        new PolicyVersionStatusChanged(
            tenant, UUID.randomUUID(), versionId, PolicyVersionStatus.NGUNG));
    service.resolve(tenant, period, "DANG", null, null);
    verify(resolution, times(2)).resolve(tenant, period, "DANG", null, null);
  }

  @Test
  void ketQuaRongKhongDuocDem() {
    when(resolution.resolve(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    assertThat(service.resolve(tenant, period, "NHA_NUOC", null, null)).isEmpty();
    assertThat(service.resolve(tenant, period, "NHA_NUOC", null, null)).isEmpty();
    verify(resolution, times(2)).resolve(tenant, period, "NHA_NUOC", null, null);
  }

  @Test
  void thamSoVaVongDoiDocQuaDemNoiDung() {
    when(resolution.content(tenant, versionId)).thenReturn(Optional.of(content()));
    assertThat(service.parameter(tenant, versionId, "tru_tien_do")).contains(0.05);
    assertThat(service.parameters(tenant, versionId)).containsKey("tru_tien_do");
    assertThat(service.taskLifecycle(tenant, versionId)).hasSize(1);
    assertThat(service.rules(tenant, versionId).versionId()).isEqualTo(versionId);
    verify(resolution, times(1)).content(tenant, versionId);
    UUID unknown = UUID.randomUUID();
    when(resolution.content(tenant, unknown)).thenReturn(Optional.empty());
    assertThat(service.taskLifecycle(tenant, unknown)).isEmpty();
    assertThat(service.rules(tenant, unknown).rules()).isEmpty();
  }

  @Test
  void khongCoBoPhanGiaiThiRong() {
    DefaultPolicyQueryService bare = new DefaultPolicyQueryService(mock(PeriodApi.class));
    assertThat(bare.resolve(tenant, period, "DANG", null, null)).isEmpty();
    assertThat(bare.parameter(tenant, versionId, "x")).isEmpty();
  }

  /** Vi đo: đọc trúng đệm p95 dưới 1 ms (tiêu chí hoàn thành CORE-05-03). */
  @Test
  void docTrungDemP95DuoiMotMiliGiay() {
    when(resolution.resolve(tenant, period, "DANG", null, null)).thenReturn(Optional.of(view));
    when(resolution.content(tenant, versionId)).thenReturn(Optional.of(content()));
    service.resolve(tenant, period, "DANG", null, null);
    service.parameter(tenant, versionId, "tru_tien_do");
    for (int i = 0; i < 20_000; i++) {
      service.resolve(tenant, period, "DANG", null, null);
      service.parameter(tenant, versionId, "tru_tien_do");
    }
    int n = 10_000;
    long[] samples = new long[n];
    for (int i = 0; i < n; i++) {
      long start = System.nanoTime();
      service.resolve(tenant, period, "DANG", null, null);
      service.parameter(tenant, versionId, "tru_tien_do");
      samples[i] = System.nanoTime() - start;
    }
    Arrays.sort(samples);
    long p95 = samples[(int) (n * 0.95)];
    assertThat(p95).as("p95 = %d ns", p95).isLessThan(1_000_000L);
  }
}
