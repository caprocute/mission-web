package vn.viettel.okrkpi.integ.domain;

/** Chiều của kết nối ({@code integ.connector.direction}). */
public enum Direction {
  VAO,
  RA,
  HAI_CHIEU;

  /** Kết nối nhận thông điệp từ hệ ngoài. */
  public boolean inbound() {
    return this != RA;
  }

  /** Kết nối gửi thông điệp ra hệ ngoài. */
  public boolean outbound() {
    return this != VAO;
  }
}
