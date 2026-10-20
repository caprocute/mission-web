package vn.viettel.okrkpi.org.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrgChangeRulesTest {

  private static final UUID T = UUID.randomUUID();
  private static final UUID R = UUID.randomUUID();

  @Test
  void sapNhapThieuTienThanTraMsg0109() {
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.SAP_NHAP, T, List.of(), 0, null))
        .isInstanceOfSatisfying(OrgRuleViolation.class, e -> {
          assertThat(e.code()).isEqualTo("ORG-422-PREDECESSOR-REQUIRED");
          assertThat(e.rule()).isEqualTo("BR-01-07");
          assertThat(e.msg()).isEqualTo("MSG-01-09");
          assertThat(e.field()).isEqualTo("relatedUnitIds");
        });
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.HOP_NHAT, T, null, 1, null))
        .hasFieldOrPropertyWithValue("msg", "MSG-01-09");
  }

  @Test
  void hopLeTheoTungLoai() {
    assertThatCode(() -> OrgChangeRules.validate(OrgChangeType.SAP_NHAP, T, List.of(R), 0, null))
        .doesNotThrowAnyException();
    assertThatCode(() -> OrgChangeRules.validate(OrgChangeType.HOP_NHAT, T, List.of(R), 1, null))
        .doesNotThrowAnyException();
    assertThatCode(() -> OrgChangeRules.validate(OrgChangeType.CHIA_TACH, T, List.of(), 2, null))
        .doesNotThrowAnyException();
    assertThatCode(() -> OrgChangeRules.validate(OrgChangeType.THANH_LAP, T, List.of(), 1, null))
        .doesNotThrowAnyException();
    assertThatCode(() -> OrgChangeRules.validate(OrgChangeType.DOI_TEN, T, List.of(), 0, "Sở mới"))
        .doesNotThrowAnyException();
    assertThatCode(() -> OrgChangeRules.validate(OrgChangeType.GIAI_THE, T, List.of(), 0, null))
        .doesNotThrowAnyException();
  }

  @Test
  void viPhamCauTruc() {
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.SAP_NHAP, T, List.of(T), 0, null))
        .hasFieldOrPropertyWithValue("field", "relatedUnitIds");
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.SAP_NHAP, T, List.of(R, R), 0, null))
        .hasFieldOrPropertyWithValue("field", "relatedUnitIds");
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.THANH_LAP, T, List.of(), 0, null))
        .hasFieldOrPropertyWithValue("field", "newUnits");
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.HOP_NHAT, T, List.of(R), 2, null))
        .hasFieldOrPropertyWithValue("field", "newUnits");
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.CHIA_TACH, T, List.of(), 0, null))
        .hasFieldOrPropertyWithValue("field", "newUnits");
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.DOI_TEN, T, List.of(), 0, " "))
        .hasFieldOrPropertyWithValue("field", "newName");
    assertThatThrownBy(() -> OrgChangeRules.validate(OrgChangeType.GIAI_THE, T, List.of(), 1, null))
        .hasFieldOrPropertyWithValue("field", "newUnits");
  }

  @Test
  void khoiDoiTuongBatBuoc() {
    assertThatThrownBy(() -> OrgChangeRules.requireSubjectGroup(null, true, "subjectGroupId"))
        .hasFieldOrPropertyWithValue("rule", "BR-01-05");
    assertThatThrownBy(() -> OrgChangeRules.requireSubjectGroup(R, false, "subjectGroupId"))
        .hasFieldOrPropertyWithValue("code", "ORG-422-SUBJECT-GROUP");
    assertThatCode(() -> OrgChangeRules.requireSubjectGroup(R, true, "x")).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @EnumSource(OrgChangeType.class)
  void moiLoaiCoLoaiQuyetDinhLuuTru(OrgChangeType type) {
    assertThat(type.orderKind()).isIn("DINH_CHINH", "SAP_NHAP", "CHIA_TACH", "GIAI_THE");
  }
}
