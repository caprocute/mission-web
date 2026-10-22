package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.okr.application.OkrReadService.KeyResultView;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Finding;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Policy;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.BasisRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.KeyResultRow;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveRow;
import vn.viettel.okrkpi.okr.application.port.OkrSimilarityPort;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị phần ghép dữ liệu của bộ kiểm tra OKR (CORE-01-04). */
class OkrCheckServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID VERSION = UUID.randomUUID();

  private final OkrReadRepository reads = mock(OkrReadRepository.class);
  private final OkrWriteRepository writes = mock(OkrWriteRepository.class);
  private final PolicyQueryApi policies = mock(PolicyQueryApi.class);
  private final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  private final OkrSimilarityPort similarity = mock(OkrSimilarityPort.class);

  private OkrCheckService service(boolean withPorts) {
    return withPorts
        ? new OkrCheckService(
            new OkrRuleChecker(), reads, writes, () -> policies, () -> org, () -> similarity)
        : new OkrCheckService(
            new OkrRuleChecker(), reads, writes, () -> null, () -> null, () -> null);
  }

  private void pack() {
    when(policies.resolve(eq(TENANT), any(), eq("DANG"), eq(null), eq(UNIT)))
        .thenReturn(
            Optional.of(
                new ResolvedPack(
                    UUID.randomUUID(), "P", VERSION, "1.2.0", "h", "k", UUID.randomUUID(), true)));
  }

  @Test
  void thamSoMacDinhKhiKhongCoGoi() {
    assertThat(service(false).policy(TENANT, PERIOD, UNIT))
        .isEqualTo(OkrRuleChecker.DEFAULT_POLICY);
    assertThat(service(true).policy(TENANT, null, UNIT)).isEqualTo(OkrRuleChecker.DEFAULT_POLICY);
    when(policies.resolve(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    assertThat(service(true).policy(TENANT, PERIOD, UNIT)).isEqualTo(OkrRuleChecker.DEFAULT_POLICY);
    assertThat(service(false).flag(TENANT, PERIOD, UNIT, "x")).isFalse();
    assertThat(service(true).flag(TENANT, null, UNIT, "x")).isFalse();
  }

  @Test
  void docThamSoTuGoi() {
    pack();
    when(policies.parameter(TENANT, VERSION, OkrCheckService.P_RECOMMENDED_MAX))
        .thenReturn(Optional.of(7));
    when(policies.parameter(TENANT, VERSION, OkrCheckService.P_VAGUE_WORDS))
        .thenReturn(Optional.of(List.of("nâng cao")));
    when(policies.parameter(TENANT, VERSION, OkrCheckService.P_TASK_VERBS))
        .thenReturn(Optional.of(List.of()));
    when(policies.parameter(TENANT, VERSION, OkrCheckService.P_UNITS))
        .thenReturn(Optional.of("không phải danh sách"));
    when(policies.parameter(TENANT, VERSION, OkrCheckService.P_RULES))
        .thenReturn(Optional.of(List.of(Map.of("id", "R1", "assert", "true"), "lạ")));
    when(policies.parameter(TENANT, VERSION, "co")).thenReturn(Optional.of(Boolean.TRUE));
    Policy p = service(true).policy(TENANT, PERIOD, UNIT);
    Policy d = OkrRuleChecker.DEFAULT_POLICY;
    assertThat(p.recommendedMax()).isEqualTo(7);
    assertThat(p.vagueWords()).containsExactly("nâng cao");
    assertThat(p.taskVerbs()).isEqualTo(d.taskVerbs());
    assertThat(p.units()).isEqualTo(d.units());
    assertThat(p.rules()).isEqualTo(d.rules());
    assertThat(service(true).flag(TENANT, PERIOD, UNIT, "co")).isTrue();

    // Quy tắc là danh sách bản đồ hợp lệ hay không hợp lệ đều không làm hỏng tham số.
    when(policies.parameter(TENANT, VERSION, OkrCheckService.P_RULES))
        .thenReturn(
            Optional.of(
                List.of(
                    Map.of(
                        "id",
                        "R1",
                        "br",
                        "BR-02-01",
                        "scope",
                        "OBJECTIVE",
                        "severity",
                        "LOI_CHAN",
                        "assert",
                        "true",
                        "message",
                        "m",
                        "serverOnly",
                        true))));
    when(policies.parameter(TENANT, VERSION, OkrCheckService.P_RECOMMENDED_MAX))
        .thenReturn(Optional.of(0));
    Policy q = service(true).policy(TENANT, PERIOD, UNIT);
    assertThat(q.recommendedMax()).isEqualTo(d.recommendedMax());
    when(policies.parameter(TENANT, VERSION, OkrCheckService.P_RULES))
        .thenReturn(Optional.of("không phải danh sách"));
    assertThat(service(true).policy(TENANT, PERIOD, UNIT).rules()).isEqualTo(d.rules());
  }

  @Test
  void kiemTraGhepCapTrenDonViCaoNhatVaTrungNhiemVu() {
    UUID parentId = UUID.randomUUID();
    ObjectiveRow o = mock(ObjectiveRow.class);
    when(o.periodId()).thenReturn(PERIOD);
    when(o.ownerUnitId()).thenReturn(UNIT);
    when(o.ownerType()).thenReturn("CA_NHAN");
    when(o.ownerPersonId()).thenReturn(UUID.randomUUID());
    when(o.parentObjectiveId()).thenReturn(parentId);
    when(o.title()).thenReturn("Nâng cao chất lượng sinh hoạt chi bộ");
    ObjectiveRow parent = mock(ObjectiveRow.class);
    when(parent.status()).thenReturn("CHO_DUYET");
    when(parent.code()).thenReturn("O1");
    when(reads.objective(TENANT, parentId)).thenReturn(Optional.of(parent));
    when(org.parentOf(eq(TENANT), any(), eq(UNIT))).thenReturn(Optional.empty());
    KeyResultRow k1 = kr("KR 1.1", "Tổ chức 10 chuyên đề");
    KeyResultRow k2 = kr("KR 1.2", "Kết nạp 5 đảng viên");
    when(similarity.similarTask(TENANT, PERIOD, UNIT, "Tổ chức 10 chuyên đề"))
        .thenReturn(Optional.of("NV-01"));
    when(similarity.similarTask(TENANT, PERIOD, UNIT, "Kết nạp 5 đảng viên"))
        .thenThrow(new IllegalStateException("không gọi được"));
    ObjectiveView view =
        new ObjectiveView(
            o,
            BigDecimal.ZERO,
            List.of(new KeyResultView(k1, BigDecimal.ZERO), new KeyResultView(k2, BigDecimal.ZERO)),
            List.of(mock(BasisRow.class)));
    List<Finding> findings = service(true).check(TENANT, view);
    assertThat(findings).extracting(Finding::similarTaskCode).contains("NV-01");
    assertThat(findings)
        .extracting(Finding::message)
        .anyMatch(m -> m.startsWith("Chưa kiểm được KR 1.2"));

    when(o.parentObjectiveId()).thenReturn(null);
    when(o.ownerType()).thenReturn("TAP_THE");
    when(o.ownerPersonId()).thenReturn(null);
    assertThat(service(false).check(TENANT, view)).isNotEmpty();
  }

  private static KeyResultRow kr(String code, String title) {
    KeyResultRow r = mock(KeyResultRow.class);
    when(r.id()).thenReturn(UUID.randomUUID());
    when(r.code()).thenReturn(code);
    when(r.title()).thenReturn(title);
    when(r.unit()).thenReturn("chuyên đề");
    when(r.targetValue()).thenReturn(BigDecimal.TEN);
    when(r.weight()).thenReturn(new BigDecimal("0.5"));
    when(r.dueDate()).thenReturn(LocalDate.of(2027, 3, 31));
    when(r.verifierPersonId()).thenReturn(UUID.randomUUID());
    return r;
  }
}
