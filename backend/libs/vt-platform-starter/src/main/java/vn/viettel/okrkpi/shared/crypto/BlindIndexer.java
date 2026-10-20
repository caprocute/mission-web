package vn.viettel.okrkpi.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;

/**
 * Tính chỉ mục mù ({@code <cột>_bidx bytea}) dài 16 byte để so khớp bằng ({@code =}) trên cột C2 đã
 * mã hoá (NEN-015, ADR-06, TL-01 "Chỉ mục mù", TL-03 "Chỉ mục mù"):
 *
 * <ol>
 *   <li>Chuẩn hoá giá trị đầu vào: Unicode {@code NFC}, cắt khoảng trắng hai đầu, gom chuỗi khoảng
 *       trắng liên tiếp thành một dấu cách đơn, chuyển chữ thường ({@link Locale#ROOT}).
 *   <li>Lấy khoá gốc {@link DekPurpose#BLIND_INDEX} của tenant (được bọc bởi OpenBao Transit {@code
 *       kek-<tenant>} và đệm trong {@link DekCache}).
 *   <li>Dẫn xuất khoá HMAC riêng cho từng cột bằng {@code HMAC-SHA256(rootKey, "bidx|" + tenantId +
 *       "|" + column)} để cùng một giá trị ở hai cột khác nhau không có cùng chỉ mục mù.
 *   <li>Tính {@code HMAC-SHA256(columnKey, normalizedValue)} và cắt lấy 16 byte đầu.
 * </ol>
 */
public final class BlindIndexer {

  /** Độ dài chuẩn của chỉ mục mù lưu trong cột {@code <cột>_bidx}: 16 byte (128 bit). */
  public static final int BLIND_INDEX_LENGTH = 16;

  private final DekService dekService;
  private final CryptoProvider cryptoProvider;

  public BlindIndexer(DekService dekService, CryptoProvider cryptoProvider) {
    this.dekService = Objects.requireNonNull(dekService, "dekService");
    this.cryptoProvider = Objects.requireNonNull(cryptoProvider, "cryptoProvider");
  }

  /**
   * Tính giá trị chỉ mục mù 16 byte cho {@code (tenantId, column, value)}.
   *
   * @param tenantId định danh tenant
   * @param column định danh cột chuẩn (ví dụ {@code "identity.user_profile.national_id"})
   * @param value giá trị bản rõ cần tìm kiếm hoặc lập chỉ mục
   * @return mảng 16 byte chỉ mục mù
   */
  public byte[] compute(UUID tenantId, String column, String value) {
    Objects.requireNonNull(tenantId, "tenantId");
    String normalizedCol = normalizeColumn(column);
    Objects.requireNonNull(value, "value");
    CachedDek rootDek = dekService.getOrCreateBlindIndexKey(tenantId);
    byte[] rootKey = rootDek.keyBytes();
    try {
      return computeWithRootKey(tenantId, rootKey, normalizedCol, value);
    } finally {
      DekCache.zeroKey(rootKey);
    }
  }

  /**
   * Tính chỉ mục mù 16 byte với khoá gốc {@code rootKey} chỉ định (dùng nội bộ và kiểm chứng vector
   * kiểm thử liên ngôn ngữ).
   */
  public byte[] computeWithRootKey(UUID tenantId, byte[] rootKey, String column, String value) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(rootKey, "rootKey");
    String normalizedCol = normalizeColumn(column);
    String normalizedVal = normalize(value);
    byte[] contextBytes =
        ("bidx|" + tenantId + "|" + normalizedCol).getBytes(StandardCharsets.UTF_8);
    byte[] columnKey = cryptoProvider.hmacSha256(rootKey, contextBytes);
    byte[] fullMac = null;
    try {
      fullMac =
          cryptoProvider.hmacSha256(columnKey, normalizedVal.getBytes(StandardCharsets.UTF_8));
      return Arrays.copyOf(fullMac, BLIND_INDEX_LENGTH);
    } finally {
      DekCache.zeroKey(columnKey);
      DekCache.zeroKey(fullMac);
    }
  }

  /**
   * Chuẩn hoá chuỗi trước khi tính chỉ mục mù: Unicode NFC, bỏ khoảng trắng thừa đầu/cuối, gom
   * khoảng trắng liên tiếp thành một dấu cách, chuyển chữ thường {@link Locale#ROOT}.
   */
  public static String normalize(String value) {
    Objects.requireNonNull(value, "value");
    String nfc = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
    if (nfc.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(nfc.length());
    boolean inSpace = false;
    for (int i = 0; i < nfc.length(); i++) {
      char c = nfc.charAt(i);
      if (Character.isWhitespace(c)) {
        if (!inSpace) {
          sb.append(' ');
          inSpace = true;
        }
      } else {
        sb.append(c);
        inSpace = false;
      }
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  private static String normalizeColumn(String column) {
    if (column == null || column.isBlank()) {
      throw new CryptoException("Tên cột tính chỉ mục mù không được null hoặc trống");
    }
    return column.trim().toLowerCase(Locale.ROOT);
  }
}
