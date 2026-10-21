package vn.viettel.okrkpi.shared.crypto;

/** Trạng thái vòng đời của khoá DEK trong bảng {@code keyring.dek} (ADR-06, TL-03). */
public enum DekStatus {
  /** Khoá đang hoạt động của tháng hiện hành: được phép mã hoá và giải mã. */
  ACTIVE,
  /** Khoá của tháng cũ đã xoay vòng: chỉ được phép giải mã, không dùng mã hoá bản ghi mới. */
  DECRYPT_ONLY,
  /**
   * Khoá đã nghỉ hưu sau khi dữ liệu đã được mã hoá lại: vẫn cho phép giải mã đối chiếu nếu cần.
   */
  RETIRED,
  /** Khoá đã huỷ bằng mật mã (crypto-shredding): cấm mọi thao tác mã hoá và giải mã. */
  DESTROYED;

  /** Khoá có được phép dùng để mã hoá dữ liệu mới hay không. */
  public boolean canEncrypt() {
    return this == ACTIVE;
  }

  /** Khoá có được phép dùng để giải mã bản mã hiện có hay không. */
  public boolean canDecrypt() {
    return this != DESTROYED;
  }
}
