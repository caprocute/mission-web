package vn.viettel.okrkpi.shared.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyAndClassificationTest {

  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void vndKhongCoPhanLeVaLamTronHalfUp() {
    assertThat(Money.of(new BigDecimal("1000.5"), Money.VND).amount()).isEqualByComparingTo("1001");
    assertThat(Money.of(new BigDecimal("1000.4"), Money.VND).amount()).isEqualByComparingTo("1000");
    assertThat(Money.vnd(1_500_000)).hasToString("1500000 VND");
    assertThat(Money.vnd(1000)).isEqualTo(Money.of("1000.0", "VND"));
  }

  @Test
  void phepTinhCungDonVi() {
    Money a = Money.of("10.25", "USD");
    Money b = Money.of("0.75", "USD");

    assertThat(a.plus(b)).isEqualTo(Money.of("11.00", "USD"));
    assertThat(a.minus(b)).isEqualTo(Money.of("9.50", "USD"));
    assertThat(a.times(new BigDecimal("0.333"))).isEqualTo(Money.of("3.41", "USD"));
    assertThat(a.negate().isNegative()).isTrue();
    assertThat(a.isNegative()).isFalse();
    assertThat(Money.zero(USD).isZero()).isTrue();
    assertThat(a.isZero()).isFalse();
    assertThat(a.compareTo(b)).isPositive();
    assertThat(b.compareTo(a)).isNegative();
  }

  @Test
  void khacDonViTienThiTuChoi() {
    Money vnd = Money.vnd(1);
    Money usd = Money.zero(USD);

    assertThatThrownBy(() -> vnd.plus(usd)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> vnd.minus(usd)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> vnd.compareTo(usd)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> vnd.plus(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> vnd.times(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Money(null, USD)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void bonMucPhanLoaiTheoAdr06() {
    assertThat(Classification.values())
        .containsExactly(
            Classification.C0, Classification.C1, Classification.C2, Classification.C3);
    assertThat(Classification.C2.isAtLeast(Classification.C1)).isTrue();
    assertThat(Classification.C1.isAtLeast(Classification.C2)).isFalse();
    assertThat(Classification.C1.isSensitive()).isFalse();
    assertThat(Classification.C2.isSensitive()).isTrue();
    assertThat(Classification.C3.isSensitive()).isTrue();
  }

  record NhanXet(@Classified(Classification.C2) String noiDung, String tieuDe) {}

  @Test
  void annotationClassifiedDocDuocLucChay() {
    RecordComponent[] components = NhanXet.class.getRecordComponents();

    assertThat(components[0].getAnnotation(Classified.class).value()).isEqualTo(Classification.C2);
    assertThat(components[1].getAnnotation(Classified.class)).isNull();
  }
}
