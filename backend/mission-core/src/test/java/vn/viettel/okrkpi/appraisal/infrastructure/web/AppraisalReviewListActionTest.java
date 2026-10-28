package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;
import vn.viettel.okrkpi.shared.authz.SqlPredicate;

/**
 * Bộ lọc dòng của {@code listAppraisalProfiles}: người có chức năng cũ (appraiser, decider) luôn đi
 * đường cũ, kể cả với trạng thái đã chốt; chỉ người không có chức năng cũ (thanh tra) và bộ lọc gồm
 * toàn trạng thái đã chốt mới dịch SCR-1301.tao (YC-PT-09). Xuất tệp không có nhánh dự phòng.
 */
class AppraisalReviewListActionTest {

  private static final SqlPredicate CD = new SqlPredicate("p.org_unit_id IN (SELECT 1)", List.of());
  private static final SqlPredicate TN = new SqlPredicate("p.tenant_id = ?", List.of("t1"));

  private static QueueFilter filter(String... statuses) {
    return new QueueFilter(
        null, null, null, true, AppraisalReviewController.statuses(List.of(statuses)), null, null);
  }

  /** Giả lập OPA theo vai trò: chức năng được cấp trả mệnh đề, còn lại luôn sai. */
  private static final class FakeOpa implements Function<String, SqlPredicate> {
    final Map<String, SqlPredicate> grants;
    final List<String> asked = new ArrayList<>();

    FakeOpa(Map<String, SqlPredicate> grants) {
      this.grants = grants;
    }

    @Override
    public SqlPredicate apply(String action) {
      asked.add(action);
      return grants.getOrDefault(action, SqlPredicate.FALSE);
    }
  }

  private static FakeOpa appraiser() {
    return new FakeOpa(Map.of("SCR-0902.xem", CD, "SCR-0903.xem", CD, "SCR-1301.tao", CD));
  }

  private static FakeOpa inspector() {
    return new FakeOpa(Map.of("SCR-1301.tao", TN));
  }

  @Test
  void appraiserVoiTrangThaiDaChotGiuDuongCu() {
    FakeOpa opa = appraiser();
    SqlPredicate p =
        AppraisalReviewController.listPredicate(
            filter("DA_QUYET_DINH", "DA_KY_SO", "DA_KHOA", "KHIEU_NAI"), true, opa);
    assertThat(p).isEqualTo(CD);
    assertThat(opa.asked).containsExactly("SCR-0902.xem");
  }

  @Test
  void thanhTraVoiTrangThaiDaChotDiTheoScr1301() {
    FakeOpa opa = inspector();
    assertThat(AppraisalReviewController.listPredicate(filter("DA_KHOA"), true, opa)).isEqualTo(TN);
    assertThat(opa.asked).containsExactly("SCR-0902.xem", "SCR-1301.tao");
    assertThat(
            AppraisalReviewController.listPredicate(
                filter("DA_QUYET_DINH", "DA_KY_SO", "DA_KHOA", "KHIEU_NAI"), true, inspector()))
        .isEqualTo(TN);
  }

  @Test
  void thanhTraVoiTrangThaiChuaChotVanBiChan() {
    for (String[] statuses :
        List.of(
            new String[] {"DA_KHOA", "CHO_THAM_DINH"},
            new String[] {"TU_DANH_GIA"},
            new String[] {"CHUA_LAP", "DA_KY_SO"},
            new String[] {"CHO_QUYET_DINH"},
            new String[] {"DA_THAM_DINH"},
            new String[] {})) {
      FakeOpa opa = inspector();
      assertThat(
              AppraisalReviewController.listPredicate(filter(statuses), true, opa).isAlwaysFalse())
          .isTrue();
      assertThat(opa.asked).doesNotContain("SCR-1301.tao");
    }
  }

  @Test
  void xuatTepKhongDiNhanhScr1301() {
    FakeOpa opa = inspector();
    assertThat(
            AppraisalReviewController.listPredicate(filter("DA_KHOA"), false, opa).isAlwaysFalse())
        .isTrue();
    assertThat(opa.asked).containsExactly("SCR-0902.xem");
  }

  @Test
  void chucNangCuTheoManHinh() {
    assertThat(AppraisalReviewController.queueAction(filter("CHO_QUYET_DINH")))
        .isEqualTo("SCR-0904.xem");
    assertThat(AppraisalReviewController.queueAction(filter("DA_THAM_DINH")))
        .isEqualTo("SCR-0903.xem");
    assertThat(AppraisalReviewController.queueAction(filter())).isEqualTo("SCR-0902.xem");
    assertThat(AppraisalReviewController.queueAction(filter("DA_KHOA"))).isEqualTo("SCR-0902.xem");
  }
}
