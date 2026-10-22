package vn.viettel.okrkpi.evidence.domain;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Vị trí tệp trên Ceph RGW (TL-03 "Kho đối tượng Ceph", "Khoá đối tượng và khử trùng"): bucket
 * {@code ev-<cell>-<tenant>} và khoá theo nội dung {@code t/<tenant>/sha256/<ab>/<băm>}. Định địa
 * chỉ theo nội dung nên cùng nội dung trong một tenant cho cùng khoá, và không thể ghi đè nội dung
 * khác vào cùng khoá; khử trùng chỉ trong tenant.
 */
public record ObjectLocation(String bucket, String key) {

  private static final Pattern CELL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,21}[a-z0-9])?");
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  public ObjectLocation {
    Objects.requireNonNull(bucket, "bucket");
    Objects.requireNonNull(key, "key");
  }

  /** Bucket minh chứng của tenant trong cell; tối đa 63 ký tự theo quy tắc tên bucket S3. */
  public static String bucketOf(String cell, UUID tenant) {
    Objects.requireNonNull(tenant, "tenant");
    String c = Objects.requireNonNull(cell, "cell").toLowerCase(Locale.ROOT);
    if (!CELL.matcher(c).matches()) {
      throw new IllegalArgumentException("Mã cell không hợp lệ cho tên bucket: " + cell);
    }
    return "ev-" + c + "-" + tenant;
  }

  /** Khoá đối tượng theo băm SHA-256 dạng hex chữ thường. */
  public static String keyOf(UUID tenant, String sha256Hex) {
    Objects.requireNonNull(tenant, "tenant");
    String h = Objects.requireNonNull(sha256Hex, "sha256Hex").toLowerCase(Locale.ROOT);
    if (!SHA256_HEX.matcher(h).matches()) {
      throw new IllegalArgumentException("Băm SHA-256 phải là 64 ký tự hex");
    }
    return "t/" + tenant + "/sha256/" + h.substring(0, 2) + "/" + h;
  }

  /** Khoá đối tượng theo băm SHA-256 32 byte. */
  public static String keyOf(UUID tenant, byte[] sha256) {
    if (sha256 == null || sha256.length != 32) {
      throw new IllegalArgumentException("Băm SHA-256 phải dài 32 byte");
    }
    return keyOf(tenant, HexFormat.of().formatHex(sha256));
  }

  /**
   * Bucket tạm của cell cho phần đang tải (không Object Lock, có vòng đời tự xoá): tệp chỉ vào
   * bucket khoá sau khi kiểm băm và kiểu, vì đối tượng đã khoá compliance không xoá được (suy
   * luận).
   */
  public static String stagingBucketOf(String cell) {
    String c = Objects.requireNonNull(cell, "cell").toLowerCase(Locale.ROOT);
    if (!CELL.matcher(c).matches()) {
      throw new IllegalArgumentException("Mã cell không hợp lệ cho tên bucket: " + cell);
    }
    return "ev-" + c + "-tam";
  }

  /** Đối tượng tạm của một phiên tải lên: {@code t/<tenant>/tai-len/<uploadId>}. */
  public static ObjectLocation staging(String cell, UUID tenant, UUID uploadId) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(uploadId, "uploadId");
    return new ObjectLocation(stagingBucketOf(cell), "t/" + tenant + "/tai-len/" + uploadId);
  }

  public static ObjectLocation of(String cell, UUID tenant, byte[] sha256) {
    return new ObjectLocation(bucketOf(cell, tenant), keyOf(tenant, sha256));
  }
}
