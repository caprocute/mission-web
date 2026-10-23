package vn.viettel.okrkpi.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.policy.domain.ParamConstraintCheck.Violation;

/** Kiểm khung tham số kế thừa có giới hạn (CORE-05-12, BS-03 DC-02). */
class ParamConstraintCheckTest {

  private static ParamConstraint frame(
      String key, String min, String max, String direction, List<String> scopes) {
    return new ParamConstraint(
        UUID.randomUUID(),
        key,
        false,
        false,
        min == null ? null : new BigDecimal(min),
        max == null ? null : new BigDecimal(max),
        direction,
        scopes);
  }

  private static Map<String, Object> params(String name, Object value) {
    return Map.of(name, Map.of("mac_dinh", value, "kieu", "so"));
  }

  @Test
  void heSoNhomNgoaiKhungBiTuChoiTrongKhungDuocNhan() {
    List<ParamConstraint> frames =
        List.of(frame("tham_so.he_so_nhom", "1.0", "1.5", "KHONG", List.of("KHOI")));
    Map<String, Object> parent = params("he_so_nhom", 1.0);

    List<Violation> rejected =
        ParamConstraintCheck.check(frames, parent, params("he_so_nhom", 1.6), null);
    assertThat(rejected).hasSize(1);
    Violation v = rejected.get(0);
    assertThat(v.paramKey()).isEqualTo("tham_so.he_so_nhom");
    assertThat(v.value()).isEqualTo(new BigDecimal("1.6"));
    assertThat(v.frame()).isEqualTo("[1; 1,5]");
    assertThat(v.message()).contains("1,6").contains("[1; 1,5]");

    assertThat(ParamConstraintCheck.check(frames, parent, params("he_so_nhom", 1.2), null))
        .isEmpty();
    assertThat(ParamConstraintCheck.check(frames, parent, params("he_so_nhom", 1.5), "KHOI"))
        .isEmpty();
  }

  @Test
  void chieuSietCapChinhVaBatTatDuocKiem() {
    ParamConstraint tang = frame("tham_so.tru_moi_ngay_cham", "0.05", null, "TANG", List.of());
    Map<String, Object> parent = params("tru_moi_ngay_cham", 0.1);
    assertThat(
            ParamConstraintCheck.check(
                List.of(tang), parent, params("tru_moi_ngay_cham", 0.08), "DON_VI"))
        .singleElement()
        .extracting(Violation::frame)
        .isEqualTo("chỉ được tăng từ 0,1");
    assertThat(
            ParamConstraintCheck.check(
                List.of(tang), parent, params("tru_moi_ngay_cham", 0.2), "DON_VI"))
        .isEmpty();

    ParamConstraint khoi = frame("he_so_nhom", "1.0", "1.5", "KHONG", List.of("KHOI"));
    assertThat(
            ParamConstraintCheck.check(
                List.of(khoi), params("he_so_nhom", 1.0), params("he_so_nhom", 1.2), "TINH"))
        .hasSize(1);
    assertThat(
            ParamConstraintCheck.check(
                List.of(khoi), params("he_so_nhom", 1.0), params("he_so_nhom", 1.0), "TINH"))
        .as("không đổi giá trị thì cấp nào cũng được")
        .isEmpty();

    ParamConstraint bat = frame("tham_so.co_tru_cham", null, null, "KHONG", List.of());
    assertThat(
            ParamConstraintCheck.check(
                List.of(bat), params("co_tru_cham", true), params("co_tru_cham", false), null))
        .hasSize(1);
    assertThat(
            ParamConstraintCheck.check(List.of(bat), params("co_tru_cham", true), Map.of(), null))
        .as("bỏ tham số cũng là bật tắt")
        .hasSize(1);
  }

  @Test
  void khongCoKhungHoacKhoaThanhPhanKhacThiKhongKiem() {
    assertThat(
            ParamConstraintCheck.check(
                List.of(), params("he_so_nhom", 1.0), params("he_so_nhom", 9), null))
        .isEmpty();
    ParamConstraint catalog = frame("catalog.standard_score", "-0.2", "0.2", "KHONG", List.of());
    assertThat(
            ParamConstraintCheck.check(
                List.of(catalog), Map.of(), Map.of("standard_score", 5), null))
        .isEmpty();
  }
}
