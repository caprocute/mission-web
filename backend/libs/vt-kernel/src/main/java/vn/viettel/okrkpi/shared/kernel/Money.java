package vn.viettel.okrkpi.shared.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Số tiền kèm đơn vị tiền tệ (TL-01 gói {@code shared.kernel}). TL-01 chỉ nêu tên kiểu; quy tắc
 * dưới đây là suy luận theo TL-03 "Kiểu dữ liệu chuẩn" (tính bằng {@link BigDecimal}, làm tròn
 * {@code HALF_UP}, không dùng {@code double}): số tiền luôn giữ đúng số chữ số thập phân của đơn vị
 * tiền (VND: 0), phép cộng trừ chỉ giữa cùng đơn vị.
 *
 * @param amount số tiền, đã chuẩn hoá thang theo đơn vị tiền
 * @param currency đơn vị tiền ISO 4217
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

  /** Đồng Việt Nam. */
  public static final Currency VND = Currency.getInstance("VND");

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  public Money {
    Objects.requireNonNull(amount, "Money.amount");
    Objects.requireNonNull(currency, "Money.currency");
    amount = amount.setScale(scaleOf(currency), ROUNDING);
  }

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  public static Money of(String amount, String currencyCode) {
    return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
  }

  public static Money vnd(long amount) {
    return new Money(BigDecimal.valueOf(amount), VND);
  }

  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  /** Nhân với hệ số, làm tròn {@code HALF_UP} về thang của đơn vị tiền. */
  public Money times(BigDecimal factor) {
    Objects.requireNonNull(factor, "factor");
    return new Money(amount.multiply(factor), currency);
  }

  public Money negate() {
    return new Money(amount.negate(), currency);
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  @Override
  public String toString() {
    return amount.toPlainString() + " " + currency.getCurrencyCode();
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "Khác đơn vị tiền: "
              + currency.getCurrencyCode()
              + " và "
              + other.currency.getCurrencyCode());
    }
  }

  private static int scaleOf(Currency currency) {
    return Math.max(currency.getDefaultFractionDigits(), 0);
  }
}
