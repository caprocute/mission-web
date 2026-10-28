package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;

/**
 * Chức năng ma trận dùng làm bộ lọc dòng của {@code listAppraisalProfiles}: chỉ bộ lọc gồm toàn
 * trạng thái đã chốt mới đi theo SCR-1301.tao (thanh tra xem toàn tenant, YC-PT-09); trạng thái
 * nháp, chờ thẩm định, chờ quyết định giữ chức năng cũ để thanh tra vẫn bị chặn.
 */
class AppraisalReviewListActionTest {

  private static QueueFilter filter(String... statuses) {
    return new QueueFilter(
        null, null, null, true, AppraisalReviewController.statuses(List.of(statuses)), null, null);
  }

  @Test
  void trangThaiDaChotDiTheoScr1301() {
    assertThat(AppraisalReviewController.listAction(filter("DA_QUYET_DINH")))
        .isEqualTo("SCR-1301.tao");
    assertThat(AppraisalReviewController.listAction(filter("DA_KHOA"))).isEqualTo("SCR-1301.tao");
    assertThat(
            AppraisalReviewController.listAction(
                filter("DA_QUYET_DINH", "DA_KY_SO", "DA_KHOA", "KHIEU_NAI")))
        .isEqualTo("SCR-1301.tao");
  }

  @Test
  void tronTrangThaiChuaChotGiuChucNangCu() {
    assertThat(AppraisalReviewController.listAction(filter("DA_KHOA", "CHO_THAM_DINH")))
        .isEqualTo("SCR-0902.xem");
    assertThat(AppraisalReviewController.listAction(filter("TU_DANH_GIA")))
        .isEqualTo("SCR-0902.xem");
    assertThat(AppraisalReviewController.listAction(filter("CHUA_LAP", "DA_KY_SO")))
        .isEqualTo("SCR-0902.xem");
    assertThat(AppraisalReviewController.listAction(filter("CHO_QUYET_DINH")))
        .isEqualTo("SCR-0904.xem");
    assertThat(AppraisalReviewController.listAction(filter("DA_THAM_DINH")))
        .isEqualTo("SCR-0903.xem");
  }

  @Test
  void boTrongTrangThaiLaHangDoiThamDinh() {
    assertThat(AppraisalReviewController.listAction(filter())).isEqualTo("SCR-0902.xem");
  }

  @Test
  void xuatTepKhongDiNhanhScr1301() {
    assertThat(AppraisalReviewController.queueAction(filter("DA_KHOA"))).isEqualTo("SCR-0902.xem");
  }
}
