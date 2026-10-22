package vn.viettel.okrkpi.platform.keyrotation;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tham chiếu KEK ghi ở cột {@code keyring.dek.kek_ref}: {@code <mount>/keys/<tên khoá>[@v<n>]}.
 *
 * <p>Tên KEK khác nhau giữa các nguồn (TL-01 {@code kek-<tenant>}, ví dụ TL-03 {@code
 * transit/keys/t-hcm-c2@v3}, INF-13 {@code kek-tenant-<tenantId>}), nên job xoay vòng chỉ đọc mount
 * và tên khoá từ {@code kek_ref} của từng dòng, không bao giờ suy tên từ mã tenant (DL-14, wiki
 * 14).
 *
 * @param mount đường gắn Transit, ví dụ {@code transit}
 * @param keyName tên khoá trên Transit
 * @param version phiên bản KEK đã bọc DEK, {@code null} khi dòng cũ chưa ghi phiên bản
 */
public record KekRef(String mount, String keyName, Integer version) {

  private static final Pattern FORMAT =
      Pattern.compile("^([A-Za-z0-9_\\-/]+?)/keys/([A-Za-z0-9_.\\-]+?)(?:@v([0-9]+))?$");

  public KekRef {
    Objects.requireNonNull(mount, "mount");
    Objects.requireNonNull(keyName, "keyName");
  }

  /** Đọc {@code kek_ref}; ném {@link IllegalArgumentException} nếu sai định dạng. */
  public static KekRef parse(String kekRef) {
    Matcher m = FORMAT.matcher(Objects.requireNonNull(kekRef, "kekRef").trim());
    if (!m.matches()) {
      throw new IllegalArgumentException("kek_ref không đúng dạng <mount>/keys/<tên>[@v<n>]");
    }
    return new KekRef(
        m.group(1), m.group(2), m.group(3) == null ? null : Integer.valueOf(m.group(3)));
  }

  /** Phiên bản KEK trong bản bọc {@code vault:v<n>:...} của OpenBao Transit. */
  public static int wrappedVersion(byte[] wrappedDek) {
    String s =
        new String(
            wrappedDek,
            0,
            Math.min(wrappedDek.length, 24),
            java.nio.charset.StandardCharsets.US_ASCII);
    Matcher m = Pattern.compile("^vault:v([0-9]+):").matcher(s);
    if (!m.find()) {
      throw new IllegalArgumentException("wrapped_dek không phải bản bọc vault:v<n>:");
    }
    return Integer.parseInt(m.group(1));
  }

  /** Cùng khoá, phiên bản mới. */
  public KekRef withVersion(int newVersion) {
    return new KekRef(mount, keyName, newVersion);
  }

  /** Khoá nhóm theo mount và tên, bỏ phiên bản. */
  public String keyPath() {
    return mount + "/keys/" + keyName;
  }

  @Override
  public String toString() {
    return version == null ? keyPath() : keyPath() + "@v" + version;
  }
}
