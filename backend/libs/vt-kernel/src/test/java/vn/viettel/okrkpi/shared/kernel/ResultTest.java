package vn.viettel.okrkpi.shared.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResultTest {

  enum Loi {
    KY_DA_KHOA,
    VUOT_GIOI_HAN
  }

  @Test
  void nhanhThanhCong() {
    Result<Integer, Loi> r = Result.ok(40);

    assertThat(r.isOk()).isTrue();
    assertThat(r.isErr()).isFalse();
    assertThat(r.valueIfOk()).contains(40);
    assertThat(r.errorIfErr()).isEmpty();
    assertThat(r.map(v -> v + 2).valueIfOk()).contains(42);
    assertThat(r.mapErr(Loi::name).valueIfOk()).contains(40);
    assertThat(r.flatMap(v -> Result.<Integer, Loi>ok(v * 2)).valueIfOk()).contains(80);
    assertThat(r.flatMap(v -> Result.<Integer, Loi>err(Loi.VUOT_GIOI_HAN)).errorIfErr())
        .contains(Loi.VUOT_GIOI_HAN);
    String folded = r.fold(v -> "ok " + v, e -> "loi " + e);
    assertThat(folded).isEqualTo("ok 40");
    assertThat(r.orElse(0)).isEqualTo(40);
    assertThat(r.orElseThrow(e -> new IllegalStateException(e.name()))).isEqualTo(40);
  }

  @Test
  void thanhCongKhongGiaTri() {
    Result<Void, Loi> r = Result.ok(null);

    assertThat(r.isOk()).isTrue();
    assertThat(r.valueIfOk()).isEmpty();
  }

  @Test
  void nhanhThatBai() {
    Result<Integer, Loi> r = Result.err(Loi.KY_DA_KHOA);

    assertThat(r.isOk()).isFalse();
    assertThat(r.isErr()).isTrue();
    assertThat(r.valueIfOk()).isEmpty();
    assertThat(r.errorIfErr()).contains(Loi.KY_DA_KHOA);
    assertThat(r.map(v -> v + 2).errorIfErr()).contains(Loi.KY_DA_KHOA);
    assertThat(r.mapErr(Loi::name).errorIfErr()).contains("KY_DA_KHOA");
    assertThat(r.flatMap(v -> Result.<Integer, Loi>ok(v * 2)).isErr()).isTrue();
    String folded = r.fold(v -> "ok " + v, e -> "loi " + e);
    assertThat(folded).isEqualTo("loi KY_DA_KHOA");
    assertThat(r.orElse(0)).isZero();
    assertThatThrownBy(() -> r.orElseThrow(e -> new IllegalStateException(e.name())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("KY_DA_KHOA");
  }

  @Test
  void loiKhongDuocRong() {
    assertThatThrownBy(() -> Result.err(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void flatMapKhongDuocTraNull() {
    Result<Integer, Loi> r = Result.ok(1);

    assertThatThrownBy(() -> r.flatMap(v -> null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void dungDuocVoiPatternMatching() {
    Result<Integer, Loi> r = Result.err(Loi.VUOT_GIOI_HAN);

    String text =
        switch (r) {
          case Result.Ok<Integer, Loi>(Integer v) -> "ok " + v;
          case Result.Err<Integer, Loi>(Loi e) -> "loi " + e;
        };
    assertThat(text).isEqualTo("loi VUOT_GIOI_HAN");
  }
}
