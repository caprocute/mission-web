package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.application.PolicyRuleService.RuleEdit;
import vn.viettel.okrkpi.policy.application.PolicyRuleService.SavedRule;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyRuleRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyRuleRepository.StoredRule;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lưu quy tắc CEL và tham số (CORE-05-04): kiểm kiểu theo khai báo của gói (BR-15-01), tham số
 * (BR-15-02..04), tổng điểm nhóm (BR-15-05), sửa phiên bản đạt kiểm thử về nháp (MSG-15-03), phiên
 * bản chờ phê duyệt bị chặn, gói kế thừa chỉ sửa quy tắc cho phép (BR-15-13).
 */
class PolicyRuleServiceTest {

  private static final String MANIFEST =
      """
      {"inputs":{"ty_le_dap_ung_tieu_chi_chung":{"type":"double"},"nhiem_vu":{"type":"list<NhiemVu>"}},
       "ngu_canh":{"nv":{"type":"NhiemVu"}},
       "params":{"tru_tien_do":{"type":"double","mac_dinh":0.05,"mien":{"min":0.0,"max":0.2}},
                 "diem_toi_da_tieu_chi_chung":{"type":"int","mac_dinh":30,"mien":{"min":30,"max":30}},
                 "kr_risk_low_streak":{"type":"int","mac_dinh":2,"mien":{"min":1,"max":6}}},
       "definitions":{"nhiem_vu":{"kpi_A":{"result_type":"DECIMAL"}}}}
      """;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = UUID.randomUUID();
  private final PolicyPackRepository packs = mock(PolicyPackRepository.class);
  private final PolicyRuleRepository rules = mock(PolicyRuleRepository.class);
  private final PolicyAudit audit = mock(PolicyAudit.class);
  private final PolicyRuleService service =
      new PolicyRuleService(packs, rules, audit, new ObjectMapper(), Clock.systemUtc());
  private final List<StoredRule> stored = new ArrayList<>();

  private PolicyPack pack(String flow) {
    PolicyPack p =
        new PolicyPack(
            UUID.randomUUID(),
            "goi",
            "Gói",
            "KHOI",
            flow,
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    when(packs.findPack(tenant, p.id())).thenReturn(Optional.of(p));
    return p;
  }

  private PolicyPackVersion version(PolicyPack p, PolicyVersionStatus s, UUID source) {
    PolicyPackVersion v =
        new PolicyPackVersion(
            UUID.randomUUID(),
            p.id(),
            "1.0.0",
            s,
            null,
            MANIFEST,
            null,
            s == PolicyVersionStatus.NHAP ? null : BigDecimal.ONE,
            null,
            false,
            null,
            null,
            null,
            null,
            source,
            "x",
            null,
            null,
            null,
            null,
            3);
    when(packs.findVersion(tenant, p.id(), "1.0.0")).thenReturn(Optional.of(v));
    when(rules.storedRules(tenant, v.id())).thenReturn(stored);
    return v;
  }

  private StoredRule existing(PolicyPackVersion v, String key, String params, long rowVersion) {
    StoredRule r =
        new StoredRule(
            new PolicyRule(
                UUID.randomUUID(), v.id(), key, "1.0", "DECIMAL", "{}", "PL", 1, params, true),
            rowVersion);
    stored.add(r);
    return r;
  }

  private static RuleEdit edit(String expression, String type, Map<String, Object> params) {
    return new RuleEdit(expression, type, null, "PL 3", 10, params, null);
  }

  private SavedRule save(PolicyPack p, String key, long expected, RuleEdit e) {
    return service.storeRule(tenant, actor, p.id(), "1.0.0", key, expected, e);
  }

  private static String code(Throwable t) {
    return ((BusinessException) t).getMessage();
  }

  @Test
  void validExpressionIsAppendedWithParams() {
    PolicyPack p = pack("NHA_NUOC");
    PolicyPackVersion v = version(p, PolicyVersionStatus.NHAP, null);
    SavedRule saved =
        save(
            p,
            "kpi_C",
            0,
            edit(
                "math.greatest(0.0, 1.0 - tham_so.tru_tien_do * double(nv.ngay_cham)) + kpi_A",
                "DECIMAL",
                Map.of("tru_tien_do", 0.05, "loai_ngay", "NGAY_LAM_VIEC")));
    assertThat(saved.statusReverted()).isFalse();
    assertThat(saved.versionStatus()).isEqualTo(PolicyVersionStatus.NHAP);
    assertThat(saved.rule().rowVersion()).isZero();
    assertThat(saved.rule().rule().roundingJson()).contains("NUA_LEN");
    verify(rules).appendRule(eq(tenant), any(), eq(actor));
    verify(audit)
        .changed(
            eq(tenant),
            eq(actor),
            eq("LUU_QUY_TAC_POLICY_PACK"),
            eq("policy_rule"),
            eq("goi@1.0.0/kpi_C"),
            any(),
            eq(0L));
  }

  @Test
  void undeclaredVariableAndWrongResultTypeAreCelTypeErrors() {
    PolicyPack p = pack("DANG");
    version(p, PolicyVersionStatus.NHAP, null);
    assertThatThrownBy(() -> save(p, "kpi_A", 0, edit("ho_so.luong * 1.0", "DECIMAL", null)))
        .satisfies(
            t -> {
              assertThat(code(t)).isEqualTo("POLICY-422-CEL-TYPE");
              BusinessException b = (BusinessException) t;
              assertThat(b.detail())
                  .startsWith("Biểu thức không hợp lệ: dùng dữ liệu không có trong ngữ cảnh")
                  .contains("tại khối kpi_A");
              assertThat(b.errors()).extracting(ProblemFieldError::rule).containsOnly("BR-15-01");
            });
    assertThatThrownBy(() -> save(p, "vuot", 0, edit("1.0 + 2.0", "BOOLEAN", null)))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).detail())
                    .contains("kết quả không đúng kiểu đã chọn"));
    assertThatThrownBy(() -> save(p, "x", 0, edit("1.0 +", "DECIMAL", null)))
        .satisfies(t -> assertThat(((BusinessException) t).detail()).contains("sai cú pháp"));
    assertThatThrownBy(() -> save(p, "x", 0, edit(" ", "DECIMAL", null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> save(p, "x", 0, edit("1.0", "SO", null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION"));
    verify(rules, never()).appendRule(any(), any(), any());
  }

  @Test
  void groupMaximaMustAddUpPerFlow() {
    PolicyPack dang = pack("DANG");
    PolicyPackVersion v = version(dang, PolicyVersionStatus.NHAP, null);
    existing(v, "nhom_thuong_xuyen", "{\"diem_toi_da\":30}", 0);
    assertThatThrownBy(
            () ->
                save(dang, "nhom_then_chot", 0, edit("1.0", "DECIMAL", Map.of("diem_toi_da", 69))))
        .satisfies(
            t -> {
              assertThat(code(t)).isEqualTo("POLICY-422-GROUP-SUM");
              assertThat(((BusinessException) t).detail())
                  .isEqualTo("Tổng điểm tối đa các nhóm là 99, phải bằng 100.");
            });
    save(dang, "nhom_then_chot", 0, edit("1.0", "DECIMAL", Map.of("diem_toi_da", 70)));

    stored.clear();
    PolicyPack nn = pack("NHA_NUOC");
    version(nn, PolicyVersionStatus.NHAP, null);
    save(
        nn,
        "nhom",
        0,
        edit(
            "1.0",
            "DECIMAL",
            Map.of("nhom", List.of(Map.of("ma", "KET_QUA_NHIEM_VU", "diem_toi_da", 70)))));
    assertThatThrownBy(
            () ->
                save(
                    nn,
                    "nhom",
                    0,
                    edit("1.0", "DECIMAL", Map.of("nhom", List.of(Map.of("diem_toi_da", 65))))))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).detail())
                    .isEqualTo("Tổng điểm tối đa các nhóm là 95, phải bằng 100."));
  }

  @Test
  void parametersOutsideTheirDomainAreRejected() {
    PolicyPack p = pack("NHA_NUOC");
    version(p, PolicyVersionStatus.NHAP, null);
    for (Map<String, Object> bad :
        List.<Map<String, Object>>of(
            Map.of("tru_tien_do", 0.25),
            Map.of("cach_tinh_chat_luong", "KHAC"),
            Map.of("loai_ngay", "NGAY_NGHI"),
            Map.of("ly_do_tra_lai", "MOT_PHAN"),
            Map.of("lam_tron", Map.of("digits", 7, "mode", "NUA_LEN")),
            Map.of("lam_tron", "1"),
            Map.of("diem_toi_da", -1),
            Map.of("kr_risk_low_streak", 9),
            Map.of("kr_risk_low_streak", 1.5),
            Map.of("diem_toi_da_tieu_chi_chung", 25))) {
      assertThatThrownBy(() -> save(p, "k", 0, edit("1.0", "DECIMAL", bad)))
          .as(bad.toString())
          .satisfies(
              t -> {
                assertThat(code(t)).isEqualTo("COMMON-422-VALIDATION");
                assertThat(((BusinessException) t).errors())
                    .extracting(ProblemFieldError::field)
                    .allMatch(f -> f.startsWith("params."));
              });
    }
    save(
        p,
        "k",
        0,
        edit(
            "1.0",
            "DECIMAL",
            Map.of(
                "lam_tron",
                Map.of("chu_so", 2, "kieu", "CAT_BO"),
                "cach_tinh_chat_luong",
                "TRU_25_MOI_LAN",
                "loai_ngay",
                "NGAY_LICH",
                "kr_risk_low_streak",
                3)));
  }

  @Test
  void testedVersionRevertsToDraftAndLaterStatusesAreFrozen() {
    PolicyPack p = pack("DANG");
    PolicyPackVersion v = version(p, PolicyVersionStatus.DAT_KIEM_THU, null);
    StoredRule old = existing(v, "kpi_A", "{}", 4);
    when(packs.storeStatus(eq(tenant), any(), eq(3L))).thenReturn(true);
    when(rules.replaceRule(eq(tenant), any(), eq(4L), eq(actor))).thenReturn(true);
    SavedRule saved = save(p, "kpi_A", 4, edit("0.5", "DECIMAL", null));
    assertThat(saved.statusReverted()).isTrue();
    assertThat(saved.versionStatus()).isEqualTo(PolicyVersionStatus.NHAP);
    assertThat(saved.rule().rowVersion()).isEqualTo(5);
    assertThat(saved.rule().rule().id()).isEqualTo(old.rule().id());
    assertThat(saved.rule().rule().overridable()).isTrue();
    verify(audit)
        .changed(
            eq(tenant),
            eq(actor),
            eq("CHUYEN_TRANG_THAI_POLICY_PACK_NHAP"),
            any(),
            any(),
            eq(v.id()),
            anyLong());

    assertThatThrownBy(() -> save(p, "kpi_A", 3, edit("0.5", "DECIMAL", null)))
        .isInstanceOf(VersionConflictException.class);
    when(packs.storeStatus(eq(tenant), any(), eq(3L))).thenReturn(false);
    assertThatThrownBy(() -> save(p, "kpi_A", 4, edit("0.5", "DECIMAL", null)))
        .isInstanceOf(VersionConflictException.class);
    when(packs.storeStatus(eq(tenant), any(), eq(3L))).thenReturn(true);
    when(rules.replaceRule(eq(tenant), any(), eq(4L), eq(actor))).thenReturn(false);
    assertThatThrownBy(() -> save(p, "kpi_A", 4, edit("0.5", "DECIMAL", null)))
        .isInstanceOf(VersionConflictException.class);

    for (PolicyVersionStatus frozen :
        List.of(
            PolicyVersionStatus.DANG_KIEM_THU,
            PolicyVersionStatus.CHO_PHE_DUYET,
            PolicyVersionStatus.DA_PHAT_HANH,
            PolicyVersionStatus.NGUNG)) {
      version(p, frozen, null);
      assertThatThrownBy(() -> save(p, "kpi_A", 4, edit("0.5", "DECIMAL", null)))
          .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-409-VERSION-FROZEN"));
    }
  }

  @Test
  void inheritedVersionOnlyEditsOverridableRules() {
    PolicyPack p = pack("DANG");
    UUID parent = UUID.randomUUID();
    PolicyPackVersion v = version(p, PolicyVersionStatus.NHAP, parent);
    existing(v, "tru_tien_do", "{}", 0);
    when(packs.findRule(tenant, parent, "kpi_A"))
        .thenReturn(
            Optional.of(
                new PolicyRule(
                    UUID.randomUUID(),
                    parent,
                    "kpi_A",
                    "1.0",
                    "DECIMAL",
                    "{}",
                    "PL",
                    1,
                    "{}",
                    false)));
    when(packs.findRule(tenant, parent, "tru_tien_do"))
        .thenReturn(
            Optional.of(
                new PolicyRule(
                    UUID.randomUUID(),
                    parent,
                    "tru_tien_do",
                    "1.0",
                    "DECIMAL",
                    "{}",
                    "PL",
                    1,
                    "{}",
                    true)));
    assertThatThrownBy(() -> save(p, "kpi_A", 0, edit("0.5", "DECIMAL", null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("POLICY-403-NOT-OVERRIDABLE"));
    when(rules.replaceRule(eq(tenant), any(), eq(0L), eq(actor))).thenReturn(true);
    SavedRule ok =
        save(p, "tru_tien_do", 0, new RuleEdit("0.5", "DECIMAL", null, null, null, null, false));
    assertThat(ok.rule().rule().overridable()).isTrue();
    assertThat(ok.rule().rule().legalRef()).isEqualTo("PL");
    save(p, "quy_tac_rieng", 0, edit("0.5", "DECIMAL", null));
  }

  @Test
  void readsRulesOfVersion() {
    PolicyPack p = pack("DANG");
    PolicyPackVersion v = version(p, PolicyVersionStatus.NHAP, null);
    StoredRule r = existing(v, "kpi_A", "{}", 2);
    assertThat(service.listRules(tenant, p.id(), "1.0.0").rules()).containsExactly(r);
    when(rules.storedRule(tenant, v.id(), "kpi_A")).thenReturn(Optional.of(r));
    assertThat(service.findRule(tenant, p.id(), "1.0.0", "kpi_A")).contains(r);
    assertThat(service.findRule(tenant, p.id(), "9.9.9", "kpi_A")).isEmpty();
  }
}
