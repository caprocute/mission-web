package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Chống chồng khoảng hiệu lực khi phát hành và sự kiện vô hiệu bộ đệm (CORE-05-03). */
class PolicyPublishOverlapTest {

  private static final Instant AT = Instant.parse("2026-10-22T02:00:00Z");
  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UUID APPROVER = UUID.randomUUID();

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final PolicyPackRepository repo = mock(PolicyPackRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final PolicyPackService service =
      new PolicyPackService(repo, mock(PolicyAudit.class), new ObjectMapper(), Clock.fixed(AT));
  private final PolicyPack pack =
      new PolicyPack(
          UUID.randomUUID(),
          "khoi-dang/quy-dinh-moi",
          "Gói",
          "KHOI_DANG",
          "DANG",
          PackOrigin.GOC_TOAN_CUC,
          null,
          "QĐ 366",
          AT,
          AUTHOR,
          0);

  private PolicyPackVersion version(
      String v, PolicyVersionStatus status, String tuKy, String denKy) {
    String manifest =
        "{\"metadata\":{\"hieu_luc\":{\"tu_ky\":"
            + (tuKy == null ? "null" : "\"" + tuKy + "\"")
            + ",\"den_ky\":"
            + (denKy == null ? "null" : "\"" + denKy + "\"")
            + "}}}";
    return new PolicyPackVersion(
        UUID.randomUUID(),
        pack.id(),
        v,
        status,
        null,
        manifest,
        "ab",
        BigDecimal.ONE,
        null,
        true,
        APPROVER,
        AT,
        AT,
        null,
        null,
        "Ghi chú",
        AT,
        AUTHOR,
        AT,
        AUTHOR,
        3);
  }

  private void given(PolicyPackVersion target, PolicyPackVersion... published) {
    service.setEvents(events);
    when(repo.findPack(tenant, pack.id())).thenReturn(Optional.of(pack));
    when(repo.findVersion(tenant, pack.id(), target.version())).thenReturn(Optional.of(target));
    when(repo.listVersions(
            eq(tenant), eq(pack.id()), eq(PolicyVersionStatus.DA_PHAT_HANH), any(), anyInt()))
        .thenReturn(List.of(published));
    when(repo.storeStatus(eq(tenant), any(), eq(3L))).thenReturn(true);
  }

  private PolicyPackVersion publish(PolicyPackVersion target) {
    return service.changeStatus(
        tenant, APPROVER, pack.id(), target.version(), 3, PolicyVersionStatus.DA_PHAT_HANH);
  }

  @Test
  void cungTuKyHoacNoiTiepKhoangMoThiDuocPhatHanhVaPhatSuKien() {
    PolicyPackVersion target = version("2.1.0", PolicyVersionStatus.CHO_PHE_DUYET, "2027-Q1", null);
    given(
        target,
        version("2.0.0", PolicyVersionStatus.DA_PHAT_HANH, "2027-Q1", null),
        version("1.0.0", PolicyVersionStatus.DA_PHAT_HANH, "2026-Q1", null),
        version("0.9.0", PolicyVersionStatus.DA_PHAT_HANH, "2025-Q1", "2026-Q4"));
    assertThat(publish(target).status()).isEqualTo(PolicyVersionStatus.DA_PHAT_HANH);
    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(event.capture());
    assertThat(event.getValue())
        .isEqualTo(
            new PolicyVersionStatusChanged(
                tenant, pack.id(), target.id(), PolicyVersionStatus.DA_PHAT_HANH));
  }

  @Test
  void khoangDongChongPhienBanKhacTuKyThiTuChoi() {
    PolicyPackVersion target = version("3.0.0", PolicyVersionStatus.CHO_PHE_DUYET, "2026-Q3", null);
    given(target, version("2.0.0", PolicyVersionStatus.DA_PHAT_HANH, "2026-Q1", "2026-Q4"));
    assertThatThrownBy(() -> publish(target))
        .isInstanceOf(BusinessException.class)
        .hasMessage("POLICY-409-VERSION-CONFLICT");
    verify(repo, never()).storeStatus(any(), any(), eq(3L));
  }

  @Test
  void khoangHieuLucSaiDangThiTuChoi() {
    PolicyPackVersion target =
        version("3.0.0", PolicyVersionStatus.CHO_PHE_DUYET, "quy 1 nam 2027", null);
    given(target);
    assertThatThrownBy(() -> publish(target)).hasMessage("POLICY-409-VERSION-CONFLICT");
  }
}
