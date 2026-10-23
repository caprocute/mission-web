package vn.viettel.okrkpi.platform.reconcile;

import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Băm tổng hợp theo bucket, không phụ thuộc thứ tự dòng.
 *
 * <ul>
 *   <li>{@link Mode#XOR}: XOR của SHA-256 từng dòng chuẩn hoá, 64 ký tự hex (TL-03).
 *   <li>{@link Mode#SUM64}: tổng modulo 2^64 của 8 byte đầu (big-endian) SHA-256 từng dòng, 16 ký
 *       tự hex; trùng thông điệp {@code KET_THUC} của {@code org.bootstrap.v1} do org-service tính
 *       (wiki trang 18). Khác XOR, hai dòng trùng nhau không triệt tiêu nhau.
 * </ul>
 *
 * Không an toàn đa luồng; mỗi lần đối soát dùng một bộ riêng.
 */
public final class HashAggregate {

  /** Phép gộp băm dòng. */
  public enum Mode {
    XOR,
    SUM64
  }

  private final Mode mode;
  private final long[] sums;
  private final byte[][] xors;
  private final long[] counts = new long[Buckets.COUNT];

  public HashAggregate(Mode mode) {
    this.mode = mode;
    this.sums = mode == Mode.SUM64 ? new long[Buckets.COUNT] : null;
    this.xors = mode == Mode.XOR ? new byte[Buckets.COUNT][32] : null;
  }

  public Mode mode() {
    return mode;
  }

  /** Cộng một dòng vào bucket của {@code id}; trả bucket. */
  public int add(Object id, String canonical) {
    int bucket = Buckets.of(id);
    addTo(bucket, canonical);
    return bucket;
  }

  /** Cộng một dòng vào bucket cho trước. */
  public void addTo(int bucket, String canonical) {
    byte[] h = RowHash.sha256(canonical);
    if (mode == Mode.SUM64) {
      long v = 0;
      for (int i = 0; i < Long.BYTES; i++) {
        v = (v << 8) | (h[i] & 0xffL);
      }
      sums[bucket] += v;
    } else {
      byte[] acc = xors[bucket];
      for (int i = 0; i < acc.length; i++) {
        acc[i] ^= h[i];
      }
    }
    counts[bucket]++;
  }

  public long count(int bucket) {
    return counts[bucket];
  }

  public long total() {
    long t = 0;
    for (long c : counts) {
      t += c;
    }
    return t;
  }

  /** Băm hex của một bucket; bucket rỗng là toàn số 0. */
  public String hex(int bucket) {
    return mode == Mode.SUM64
        ? HexFormat.of().toHexDigits(sums[bucket])
        : HexFormat.of().formatHex(xors[bucket]);
  }

  /** Băm của các bucket trong phạm vi, kể cả bucket rỗng. */
  public Map<Integer, String> hashes(Collection<Integer> buckets) {
    Map<Integer, String> out = new LinkedHashMap<>();
    for (int b : buckets) {
      out.put(b, hex(b));
    }
    return out;
  }
}
