package vn.viettel.okrkpi.policy.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.contract.policies.model.PolicyProspectGroupCode;
import vn.viettel.okrkpi.contract.policies.model.ProspectCriteria;
import vn.viettel.okrkpi.contract.policies.model.RatingLevel;
import vn.viettel.okrkpi.shared.authz.OperationPolicyCatalog;

/** {@code getProspectCriteria} đọc khối {@code phan_nhom} của khai báo (SCR-1002, BR-10-07). */
class ProspectCriteriaControllerTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID PACK = UUID.fromString("d0000000-0000-4000-8000-0000000c0001");

  @Test
  void docKhoiPhanNhomVaThamSoMoOKyQuy() throws Exception {
    JsonNode manifest =
        JSON.readTree(
            """
            {"params": {"phan_nhom.mo_o_ky_quy": {"type": "bool", "mac_dinh": true}},
             "phan_nhom": {"mo_o_ky_quy": "phan_nhom.mo_o_ky_quy", "legal_ref": "PL 5",
               "nhom": [{"groupCode": "NHOM_1", "name": "Phát triển vượt trội", "criteria": "Tiêu chí 1",
                         "excludedRatingLevels": ["KHTNV"], "excludeReassignmentFlag": true},
                        {"groupCode": "NHOM_4", "name": "Nhóm 4 – Cần xem xét bố trí", "criteria": "Tiêu chí 4"}]}}
            """);
    ProspectCriteria c = ProspectCriteriaController.criteria(PACK, "2.1.0", manifest);
    assertThat(c.getOpenInQuarter()).isTrue();
    assertThat(c.getLegalRef()).isEqualTo("PL 5");
    assertThat(c.getGroups()).hasSize(2);
    assertThat(c.getGroups().get(0).getGroupCode()).isEqualTo(PolicyProspectGroupCode.NHOM_1);
    assertThat(c.getGroups().get(0).getName()).isEqualTo("Nhóm 1 – Phát triển vượt trội");
    assertThat(c.getGroups().get(0).getExcludedRatingLevels()).containsExactly(RatingLevel.KHTNV);
    assertThat(c.getGroups().get(0).getExcludeReassignmentFlag()).isTrue();
    assertThat(c.getGroups().get(1).getName()).isEqualTo("Nhóm 4 – Cần xem xét bố trí");
    assertThat(c.getGroups().get(1).getExcludedRatingLevels()).isEmpty();
    assertThat(c.getGroups().get(1).getExcludeReassignmentFlag()).isFalse();
  }

  @Test
  void phienBanChuaKhaiBaoTraBonNhomMacDinhVaKhongMoOKyQuy() throws Exception {
    ProspectCriteria c =
        ProspectCriteriaController.criteria(PACK, "1.4.0", JSON.readTree("{\"params\": {}}"));
    assertThat(c.getOpenInQuarter()).isFalse();
    assertThat(c.getLegalRef()).isEqualTo(ProspectCriteriaController.LEGAL_REF);
    assertThat(c.getGroups())
        .extracting(g -> g.getGroupCode().getValue())
        .containsExactly("NHOM_1", "NHOM_2", "NHOM_3", "NHOM_4");
  }

  @Test
  void vaiTroLayTheoHopDong() {
    assertThat(OperationPolicyCatalog.getDefault().require("getProspectCriteria").roles())
        .containsExactlyInAnyOrder("leader", "appraiser");
  }
}
