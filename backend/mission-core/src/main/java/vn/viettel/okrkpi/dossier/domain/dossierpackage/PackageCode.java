package vn.viettel.okrkpi.dossier.domain.dossierpackage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Mã gói {@code DP-yyyy-{Q/N}-nnnn} (hợp đồng {@code DossierPackage.code}): năm của kỳ, loại gói và
 * số thứ tự tăng dần trong tenant theo năm và loại.
 */
public final class PackageCode {

  public static final Pattern PATTERN = Pattern.compile("^DP-\\d{4}-[QN]-\\d{4}$");
  public static final int MAX_SEQUENCE = 9999;

  private PackageCode() {}

  /** Tiền tố chung của mọi mã cùng năm và loại, ví dụ {@code DP-2026-N-}. */
  public static String prefix(int year, PackageKind kind) {
    Objects.requireNonNull(kind, "kind");
    if (year < 1000 || year > 9999) {
      throw new IllegalArgumentException("Năm của kỳ không hợp lệ: " + year);
    }
    return "DP-" + year + "-" + kind.codeLetter() + "-";
  }

  /** Mã với số thứ tự {@code sequence} (1..9999). */
  public static String of(int year, PackageKind kind, int sequence) {
    if (sequence < 1 || sequence > MAX_SEQUENCE) {
      throw new IllegalStateException("Đã hết số thứ tự gói hồ sơ trong năm " + year);
    }
    return prefix(year, kind) + String.format("%04d", sequence);
  }

  /** Năm của kỳ theo khoá phân vùng. */
  public static int yearOf(int periodBucket) {
    return periodBucket / 10;
  }
}
