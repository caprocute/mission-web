package vn.viettel.okrkpi.workflow.domain.replica;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tình trạng công tác nào còn nhận được bước xử lý (DV-06-10, EVT-16). */
class EmploymentStatusTest {

  @Test
  void conCongTacHoacTamNghiThiDuocNhanViec() {
    assertThat(EmploymentStatus.allowsAssignment("DANG_CONG_TAC")).isTrue();
    assertThat(EmploymentStatus.allowsAssignment("TAM_NGHI")).isTrue();
    assertThat(EmploymentStatus.allowsAssignment(" DANG_CONG_TAC ")).isTrue();
  }

  @Test
  void nghiHuuThoiViecHoacGiaTriLaThiKhong() {
    assertThat(EmploymentStatus.allowsAssignment("NGHI_HUU")).isFalse();
    assertThat(EmploymentStatus.allowsAssignment("THOI_VIEC")).isFalse();
    assertThat(EmploymentStatus.allowsAssignment("dang_cong_tac")).isFalse();
    assertThat(EmploymentStatus.allowsAssignment("KHONG_RO")).isFalse();
    assertThat(EmploymentStatus.allowsAssignment(null)).isFalse();
  }

  @Test
  void docGiaTriThoCuaSuKien() {
    assertThat(EmploymentStatus.parse("NGHI_HUU")).contains(EmploymentStatus.NGHI_HUU);
    assertThat(EmploymentStatus.parse(null)).isEmpty();
    assertThat(EmploymentStatus.parse("")).isEmpty();
    assertThat(EmploymentStatus.NGHI_HUU.stillWorking()).isFalse();
    assertThat(EmploymentStatus.TAM_NGHI.stillWorking()).isTrue();
  }
}
