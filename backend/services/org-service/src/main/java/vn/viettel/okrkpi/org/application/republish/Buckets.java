package vn.viettel.okrkpi.org.application.republish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 256 bucket băm của đối soát bản sao tổ chức (TL-03 bảng đối soát, DL-12) và băm tổng theo bucket ở thông điệp
 * đánh dấu {@code KET_THUC}.
 * <ul>
 *   <li>Bucket của thực thể là byte đầu của SHA-256 trên chuỗi mã thực thể dạng chuẩn (chữ thường).
 *   <li>Băm một bản ghi là 8 byte đầu (big-endian) của SHA-256 trên {@code loại|mã|ngày hiệu lực|sourceVersion};
 *       nhân sự không có ngày hiệu lực ghi {@code -}.
 *   <li>Băm tổng của bucket là tổng modulo 2^64 của băm các bản ghi, viết hex 16 ký tự: không phụ thuộc thứ tự nhận
 *       nên bên nhận tính lại được từ bản sao mà không cần sắp xếp (suy luận, wiki trang 18).
 * </ul>
 */
public final class Buckets {

  /** Số bucket băm. */
  public static final int COUNT = 256;

  private Buckets() {
  }

  /** Bucket 0..255 của một thực thể. */
  public static int of(UUID id) {
    return sha256(id.toString())[0] & 0xff;
  }

  /** Băm một bản ghi thực thể đã phát. */
  public static long digest(EntityKind kind, UUID id, LocalDate validFrom, long sourceVersion) {
    byte[] h = sha256(kind.name() + "|" + id + "|" + (validFrom == null ? "-" : validFrom) + "|" + sourceVersion);
    long v = 0;
    for (int i = 0; i < Long.BYTES; i++) {
      v = (v << 8) | (h[i] & 0xffL);
    }
    return v;
  }

  static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Bộ cộng dồn băm và số lượng theo bucket, theo loại. */
  public static final class Accumulator {

    private final long[] sums = new long[COUNT];
    private final Map<EntityKind, Long> counts = new LinkedHashMap<>();

    public void add(EntityKind kind, UUID id, LocalDate validFrom, long sourceVersion) {
      sums[of(id)] += digest(kind, id, validFrom, sourceVersion);
      counts.merge(kind, 1L, Long::sum);
    }

    public long count(EntityKind kind) {
      return counts.getOrDefault(kind, 0L);
    }

    /** Số bản ghi theo loại, khoá là tên loại ({@code ORG_UNIT}, {@code PERSON}, {@code ASSIGNMENT}). */
    public Map<String, Long> counts(Collection<EntityKind> kinds) {
      Map<String, Long> out = new LinkedHashMap<>();
      for (EntityKind k : kinds) {
        out.put(k.name(), count(k));
      }
      return out;
    }

    /** Băm tổng của mỗi bucket trong phạm vi (kể cả bucket rỗng, giá trị 0), khoá là số bucket thập phân. */
    public Map<String, String> hashes(Collection<Integer> buckets) {
      Map<String, String> out = new LinkedHashMap<>();
      for (int b : buckets) {
        out.put(Integer.toString(b), HexFormat.of().toHexDigits(sums[b]));
      }
      return out;
    }
  }
}
