package vn.viettel.okrkpi.scoring.domain;

import java.util.Arrays;
import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.Jcs;

/**
 * Tài liệu JSON ở dạng chuẩn hoá RFC 8785 + NFC kèm SHA-256 của nó: {@code input_doc} và {@code
 * input_hash}, {@code result_doc} và {@code result_hash} (TL-03 "Schema scoring"). Hai tài liệu
 * cùng nội dung luôn cho cùng băm, nên tính lại so được bằng băm.
 */
public final class CanonicalDoc {

  private final String json;
  private final byte[] hash;

  private CanonicalDoc(String json) {
    this.json = json;
    this.hash = Jcs.sha256(json);
  }

  /** Chuẩn hoá một cây giá trị (Map, List, chuỗi, số, boolean, null). */
  public static CanonicalDoc of(Object value) {
    return new CanonicalDoc(Jcs.write(Objects.requireNonNull(value, "value")));
  }

  /** Chuẩn hoá một tài liệu JSON dạng văn bản. */
  public static CanonicalDoc parse(String json) {
    return new CanonicalDoc(Jcs.canonicalize(json));
  }

  public String json() {
    return json;
  }

  /** SHA-256 32 byte; trả bản sao. */
  public byte[] hash() {
    return hash.clone();
  }

  public String hashHex() {
    return Jcs.hex(hash);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof CanonicalDoc other
        && Arrays.equals(hash, other.hash)
        && json.equals(other.json);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(hash);
  }

  @Override
  public String toString() {
    return "CanonicalDoc[" + hashHex() + "]";
  }
}
