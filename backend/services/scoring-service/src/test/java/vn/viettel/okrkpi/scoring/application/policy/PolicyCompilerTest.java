package vn.viettel.okrkpi.scoring.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.scoring.application.port.DecisionTables;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.PolicyRuleSource;
import vn.viettel.okrkpi.shared.error.BusinessException;

class PolicyCompilerTest {

  static final AtomicInteger TABLE_LOADS = new AtomicInteger();

  /** Bảng giả: ghi nhận số lần nạp, trả mức theo biến {@code diem}. */
  static final DecisionTables TABLES =
      (hash, tables) -> {
        TABLE_LOADS.incrementAndGet();
        return new DecisionTables.Compiled() {
          @Override
          public Set<String> tableKeys() {
            return Set.of("xep_loai");
          }

          @Override
          public Map<String, Object> evaluate(String key, Map<String, Object> vars) {
            return Map.of("xep_loai", ((Number) vars.get("diem")).doubleValue() >= 90 ? "A" : "B");
          }
        };
      };

  static PolicyCompiler compiler() {
    return new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), TABLES);
  }

  @Test
  void bienDichGoiMauVaTinhDuocQuyTac() {
    CompiledPolicy p = compiler().compile(SamplePolicyPack.content());

    assertThat(p.rules()).containsKeys("kpi_A", "kpi_nhiem_vu", "diem", "muc_cuoi", "diem_nam");
    assertThat(p.rules().keySet().iterator().next()).isEqualTo("kpi_A");
    assertThat(p.contentHash()).hasSize(64);
    assertThat(p.pinned().version()).isEqualTo("2.1.0");
    assertThat(p.params()).containsKey("tru_tien_do");
    assertThat(p.manifest()).containsKey("inputs");
    Map<String, Object> nv = Map.of("ke_hoach", 4L, "so_luong_duyet", 3L);
    assertThat(p.evaluate("kpi_A", Map.of("nv", nv))).isEqualTo(0.75);
    assertThat(p.evaluate("diem", Map.of("diem_ket_qua", 86.25))).isEqualTo(86.3);
    assertThat(p.rules().get("diem").legalRef()).isEqualTo("BR-09-03");
    assertThat(p.decide("xep_loai", Map.of("diem", 91))).containsEntry("xep_loai", "A");
    assertThat(p.decisions().tableKeys()).containsExactly("xep_loai");
    assertThatThrownBy(() -> p.evaluate("khong_co", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bieuThucSaiKieuBiTuChoiKhiNap() {
    PolicyContent bad =
        SamplePolicyPack.content(
            SamplePolicyPack.content().pinned().packId(),
            SamplePolicyPack.content().pinned().versionId(),
            "2.1.1",
            Map.of("kpi_A", "'chuoi' + 1", "kpi_B", "khong_khai_bao * 2.0"));

    assertThatThrownBy(() -> compiler().compile(bad))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getMessage()).isEqualTo("POLICY-422-CEL-TYPE");
              assertThat(e.errors())
                  .extracting(f -> f.field())
                  .containsExactlyInAnyOrder("rules.kpi_A", "rules.kpi_B");
            });
  }

  @Test
  void kieuKhaiBaoTheoManifest() {
    assertThat(PolicyCompiler.declaredType("list<NhiemVu>")).isEqualTo(CelValueType.LIST_DYN);
    assertThat(PolicyCompiler.declaredType("double")).isEqualTo(CelValueType.DOUBLE);
    assertThat(PolicyCompiler.declaredType("int")).isEqualTo(CelValueType.INT);
    assertThat(PolicyCompiler.declaredType("bool")).isEqualTo(CelValueType.BOOL);
    assertThat(PolicyCompiler.declaredType("string")).isEqualTo(CelValueType.STRING);
    assertThat(PolicyCompiler.declaredType("NhiemVu")).isEqualTo(CelValueType.MAP_STRING_DYN);
    assertThat(PolicyCompiler.resultType(null)).isEqualTo(CelValueType.DECIMAL);
    assertThat(PolicyCompiler.resultType("STRING")).isEqualTo(CelValueType.STRING);
    assertThat(PolicyCompiler.environment(Map.of("inputs", Map.of("x", "int"))))
        .containsEntry("x", CelValueType.INT)
        .containsEntry("tham_so", CelValueType.MAP_STRING_DYN);
    assertThat(
            PolicyCompiler.environment(
                Map.of("definitions", Map.of("s", "khong-phai-map", "t", Map.of("y", "x")))))
        .doesNotContainKey("y");
  }

  @Test
  void quyTacKhongLamTronGiuNguyenGiaTri() {
    PolicyRuleSource r = new PolicyRuleSource("tong", "a + b", null, 1, null, null, null);
    PolicyContent c =
        new PolicyContent(
            SamplePolicyPack.content().pinned(),
            Map.of("inputs", Map.of("a", "double", "b", "double")),
            List.of(r),
            List.of());
    CompiledPolicy p = compiler().compile(c);
    assertThat(p.params()).isEmpty();
    assertThat(p.evaluate("tong", Map.of("a", 0.1, "b", 0.2))).isEqualTo(0.1 + 0.2);
  }
}
