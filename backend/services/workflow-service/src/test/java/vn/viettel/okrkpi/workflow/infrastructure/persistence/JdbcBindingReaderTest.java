package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalRule;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;

/** Đọc {@code wf_binding.params_json} theo khoá TL-01 và TL-02b. */
class JdbcBindingReaderTest {

  private final JdbcBindingReader reader = new JdbcBindingReader(null, new ObjectMapper());

  @Test
  void khoaThieuLayMacDinhKhoi() {
    assertThat(reader.parse(null, ApprovalFlow.DANG))
        .isEqualTo(ApprovalParams.defaults(ApprovalFlow.DANG));
    assertThat(reader.parse("{}", ApprovalFlow.NHA_NUOC))
        .isEqualTo(ApprovalParams.defaults(ApprovalFlow.NHA_NUOC));
  }

  @Test
  void docCapTuyChinhVaThoiHanMoiBuoc() {
    ApprovalParams p =
        reader.parse(
            "{\"remindAfter\":\"PT12H\",\"allowDelegation\":false,\"signMethod\":\"USB_TOKEN\","
                + "\"thoi_han_moi_buoc\":\"P5D_LV\",\"cap\":[{\"vai_tro\":\"appraiser\"},"
                + "{\"vai_tro\":\"decider\",\"pham_vi\":\"CAP_UY_CO_THAM_QUYEN\",\"han_ngay_lam_viec\":7}]}",
            ApprovalFlow.DANG);
    assertThat(p.remindAfter()).isEqualTo("PT12H");
    assertThat(p.allowDelegation()).isFalse();
    assertThat(p.signMethod()).isEqualTo("USB_TOKEN");
    assertThat(p.levels())
        .containsExactly(
            new ApprovalRule(1, "appraiser", ApprovalScope.DON_VI_TRUC_TIEP, 5),
            new ApprovalRule(2, "decider", ApprovalScope.CAP_UY_CO_THAM_QUYEN, 7));
  }

  @Test
  void thamSoSaiThiTuChoi() {
    assertThatThrownBy(() -> reader.parse("{khong-phai-json", ApprovalFlow.DANG))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                reader.parse(
                    "{\"cap\":[{\"vai_tro\":\"leader\",\"pham_vi\":\"KHAC\"}]}", ApprovalFlow.DANG))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(() -> reader.parse("{\"remindAfter\":\"2 ngay\"}", ApprovalFlow.DANG))
        .isInstanceOf(ApprovalSetupException.class);
  }
}
