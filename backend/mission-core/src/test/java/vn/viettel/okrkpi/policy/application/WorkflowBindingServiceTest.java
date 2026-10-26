package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.policy.application.WorkflowBindingService.BindingEdit;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.WorkflowBindings;
import vn.viettel.okrkpi.policy.application.port.WorkflowBindings.Versioned;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lớp công bố cấu hình luồng duyệt (CORE-05-11): phiên bản từ {@code DAT_KIEM_THU} bị chặn, kỳ bắt
 * đầu đã khoá bị chặn, lưu thành công thì chép vào manifest để băm nội dung phủ tham số luồng, mô
 * phỏng chỉ chuyển tiếp.
 */
class WorkflowBindingServiceTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = UUID.randomUUID();
  private final UUID periodId = UUID.randomUUID();
  private final PolicyPackRepository packs = mock(PolicyPackRepository.class);
  private final WorkflowBindings bindings = mock(WorkflowBindings.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final PolicyAudit audit = mock(PolicyAudit.class);
  private final WorkflowBindingService service =
      new WorkflowBindingService(packs, bindings, () -> periods, audit, new ObjectMapper());

  private PolicyPack pack() {
    PolicyPack p =
        new PolicyPack(
            UUID.randomUUID(),
            "goi",
            "Gói",
            "KHOI",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    when(packs.findPack(tenant, p.id())).thenReturn(Optional.of(p));
    return p;
  }

  private PolicyPackVersion version(PolicyPack p, PolicyVersionStatus s) {
    PolicyPackVersion v =
        new PolicyPackVersion(
            UUID.randomUUID(),
            p.id(),
            "2.1.0",
            s,
            null,
            "{}",
            null,
            s == PolicyVersionStatus.NHAP ? null : BigDecimal.ONE,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            "x",
            null,
            null,
            null,
            null,
            1);
    when(packs.findVersion(tenant, p.id(), "2.1.0")).thenReturn(Optional.of(v));
    return v;
  }

  private void openPeriod() {
    when(periods.findById(tenant, PeriodId.of(periodId)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(periodId),
                    tenant,
                    "2027-Q1",
                    "Quý I/2027",
                    LocalDate.of(2027, 1, 1),
                    LocalDate.of(2027, 3, 31),
                    PeriodStatus.MO)));
  }

  private BindingEdit edit() {
    return new BindingEdit(
        "duyet-ho-so-danh-gia",
        3,
        periodId,
        Map.of("flow", "DANG", "fromPeriodId", periodId.toString()),
        Map.of("so_cap", 4));
  }

  private Versioned save(PolicyPack p, BindingEdit e) {
    return service.save(tenant, actor, p.id(), "2.1.0", "DANH_GIA", 0, "k-1", e);
  }

  private static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @Test
  void luuThanhCongChepVaoManifestVaGhiKiemToan() {
    PolicyPack p = pack();
    PolicyPackVersion v = version(p, PolicyVersionStatus.NHAP);
    openPeriod();
    UUID id = UUID.randomUUID();
    when(bindings.save(eq(tenant), eq(v.id()), eq("DANH_GIA"), eq(0L), anyString(), any()))
        .thenReturn(new Versioned(Map.of("id", id.toString(), "rowVersion", 1), 1));
    when(packs.storeWorkflowBinding(eq(tenant), eq(v.id()), eq("DANH_GIA"), anyString(), eq(actor)))
        .thenReturn(true);

    Versioned saved = save(p, edit());

    assertThat(saved.rowVersion()).isEqualTo(1);
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(bindings).save(eq(tenant), eq(v.id()), eq("DANH_GIA"), eq(0L), key.capture(), any());
    assertThat(key.getValue())
        .isEqualTo(WorkflowBindingService.downstreamKey(v.id(), "DANH_GIA", "k-1"))
        .hasSizeLessThanOrEqualTo(64);
    ArgumentCaptor<String> copy = ArgumentCaptor.forClass(String.class);
    verify(packs)
        .storeWorkflowBinding(eq(tenant), eq(v.id()), eq("DANH_GIA"), copy.capture(), eq(actor));
    assertThat(copy.getValue())
        .contains("\"templateKey\":\"duyet-ho-so-danh-gia\"", "\"so_cap\":4")
        .doesNotContain("processKind");
    verify(audit)
        .changed(
            eq(tenant),
            eq(actor),
            eq("LUU_THAM_SO_LUONG_DUYET"),
            eq("wf_binding"),
            eq("goi@2.1.0/DANH_GIA"),
            eq(id),
            eq(1L));
  }

  @Test
  void phienBanTuDatKiemThuTroDiBiDongBang() {
    for (PolicyVersionStatus s :
        List.of(
            PolicyVersionStatus.DAT_KIEM_THU,
            PolicyVersionStatus.CHO_PHE_DUYET,
            PolicyVersionStatus.DA_PHAT_HANH)) {
      PolicyPack p = pack();
      version(p, s);
      assertThatThrownBy(() -> save(p, edit()))
          .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-409-VERSION-FROZEN"));
    }
    verifyNoInteractions(bindings);
  }

  @Test
  void kyBatDauDaKhoaBiChanVaThieuKyLa422() {
    PolicyPack p = pack();
    version(p, PolicyVersionStatus.NHAP);
    openPeriod();
    doThrow(new PeriodLockedException(PeriodId.of(periodId)))
        .when(periods)
        .assertWritable(tenant, PeriodId.of(periodId));
    assertThatThrownBy(() -> save(p, edit()))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-409-PERIOD-LOCKED"));

    BindingEdit noPeriod =
        new BindingEdit("duyet-ho-so-danh-gia", 3, null, Map.of(), Map.of("so_cap", 4));
    assertThatThrownBy(() -> save(p, noPeriod))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    BindingEdit unknown =
        new BindingEdit("duyet-ho-so-danh-gia", 3, UUID.randomUUID(), Map.of(), Map.of());
    assertThatThrownBy(() -> save(p, unknown))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    BindingEdit noTemplate = new BindingEdit(" ", 3, periodId, Map.of(), Map.of());
    assertThatThrownBy(() -> save(p, noTemplate))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    verifyNoInteractions(bindings);
  }

  @Test
  void phienBanRoiNhapTrongLucLuuThiQuayLui() {
    PolicyPack p = pack();
    PolicyPackVersion v = version(p, PolicyVersionStatus.NHAP);
    openPeriod();
    when(bindings.save(any(), any(), any(), anyLong(), any(), any()))
        .thenReturn(new Versioned(Map.of(), 2));
    when(packs.storeWorkflowBinding(any(), any(), any(), any(), any())).thenReturn(false);
    assertThatThrownBy(() -> save(p, edit()))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-409-VERSION-FROZEN"));
    verify(audit, never()).changed(any(), any(), any(), any(), any(), any(), anyLong());
    assertThat(v.status()).isEqualTo(PolicyVersionStatus.NHAP);
  }

  @Test
  void docDanhSachVaPhienBanKhongCoLa404() {
    PolicyPack p = pack();
    PolicyPackVersion v = version(p, PolicyVersionStatus.DA_PHAT_HANH);
    when(bindings.list(tenant, v.id())).thenReturn(List.of(Map.of("processKind", "DANH_GIA")));
    assertThat(service.list(tenant, p.id(), "2.1.0").items()).hasSize(1);
    when(bindings.find(tenant, v.id(), "DANH_GIA"))
        .thenReturn(Optional.of(new Versioned(Map.of(), 3)));
    assertThat(service.find(tenant, p.id(), "2.1.0", "DANH_GIA")).isPresent();
    assertThatThrownBy(() -> service.list(tenant, p.id(), "9.9.9"))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> service.find(tenant, UUID.randomUUID(), "2.1.0", "DANH_GIA"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void moPhongChiChuyenTiepKhongGhi() {
    when(bindings.simulate(eq(tenant), any(), eq("policy:mo-phong:k-9")))
        .thenReturn(Map.of("steps", List.of()));
    assertThat(service.simulate(tenant, Map.of("templateKey", "x"), "k-9")).containsKey("steps");
    verifyNoInteractions(packs, audit);
  }

  @Test
  void khoaGuiWorkflowServiceGonTrong64KyTuVaOnDinh() {
    UUID ver = UUID.randomUUID();
    String caller = UUID.randomUUID().toString();
    String k = WorkflowBindingService.downstreamKey(ver, "KE_HOACH_QUY", caller);
    assertThat(k).hasSizeLessThanOrEqualTo(64).startsWith("policy:");
    assertThat(WorkflowBindingService.downstreamKey(ver, "KE_HOACH_QUY", caller)).isEqualTo(k);
    assertThat(WorkflowBindingService.downstreamKey(ver, "OKR", caller)).isNotEqualTo(k);
  }
}
