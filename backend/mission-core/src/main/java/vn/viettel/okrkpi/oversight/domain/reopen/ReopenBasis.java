package vn.viettel.okrkpi.oversight.domain.reopen;

import java.util.UUID;

/**
 * Căn cứ của yêu cầu ({@code basis_ref}): khiếu nại, cảnh báo, đợt hậu kiểm của MOD-07 hoặc quyết
 * định hậu kiểm ngoài hệ thống (chỉ có số quyết định).
 */
public record ReopenBasis(Kind kind, UUID id, String code) {

  public enum Kind {
    KHIEU_NAI,
    CANH_BAO,
    HAU_KIEM,
    QUYET_DINH
  }

  public ReopenBasis {
    if (kind == null) {
      throw new IllegalArgumentException("kind");
    }
    code = code == null || code.isBlank() ? null : code.strip();
  }

  /** Căn cứ trong MOD-07 cần mã đối tượng; quyết định ngoài hệ thống cần số quyết định. */
  public boolean complete() {
    return kind == Kind.QUYET_DINH ? code != null : id != null;
  }

  /** Giá trị cột {@code basis_ref} ({@code varchar(200)}): {@code KIND:id} hoặc {@code KIND:số}. */
  public String ref() {
    String v = kind.name() + ":" + (id != null ? id.toString() : code);
    return v.length() <= 200 ? v : v.substring(0, 200);
  }
}
