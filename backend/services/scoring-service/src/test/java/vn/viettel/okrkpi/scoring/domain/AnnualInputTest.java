package vn.viettel.okrkpi.scoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnnualInputTest {

  private static final UUID ID = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5a01");
  private static final String H = "0".repeat(64);

  private static QuarterResult q(int quarter) {
    return new QuarterResult(quarter, BigDecimal.TEN, "HTNV", true, H, "2.1.0");
  }

  @Test
  void quySapTheoThuTuVaKhongXepLoaiThiMucRong() {
    QuarterResult none = new QuarterResult(1, BigDecimal.ZERO, null, false, H, "2.1.0");
    AnnualInput in = new AnnualInput(ID, ID, "DANG", List.of(q(3), none), Map.of("x", 1));
    assertThat(in.quarters()).extracting(QuarterResult::quarter).containsExactly(1, 3);
    assertThat(none.rating()).isEmpty();
    List<?> list = (List<?>) in.variables().get(AnnualInput.QUARTERS);
    assertThat(list).hasSize(2);
    assertThat(in.document(Map.of("nam.cach_tinh", "TRUNG_BINH_QUY")).json())
        .contains("\"ky\":\"NAM\"")
        .contains("\"input_hash\":\"" + H + "\"")
        .contains("\"tham_so_nam\":{\"nam.cach_tinh\":\"TRUNG_BINH_QUY\"}");
  }

  @Test
  void tuChoiDauVaoSai() {
    assertThatThrownBy(() -> new QuarterResult(5, BigDecimal.ONE, "HTNV", true, H, "1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuarterResult(1, BigDecimal.ONE, "", true, H, "1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuarterResult(1, BigDecimal.ONE, "HTNV", true, "ABC", "1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuarterResult(1, BigDecimal.ONE, "HTNV", true, H, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuarterResult(1, BigDecimal.ONE, "HTNV", true, H, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnnualInput(ID, ID, "KHAC", List.of(q(1)), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnnualInput(ID, ID, "DANG", List.of(q(1), q(1)), Map.of()))
        .hasMessageContaining("Trùng quý");
    assertThatThrownBy(() -> new AnnualInput(ID, ID, "DANG", List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AnnualInput(ID, ID, "DANG", List.of(q(1)), Map.of("ket_qua_quy", 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnnualInput(ID, ID, "DANG", List.of(q(1)), Map.of("x", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
