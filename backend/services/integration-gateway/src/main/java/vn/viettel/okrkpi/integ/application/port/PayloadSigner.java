package vn.viettel.okrkpi.integ.application.port;

/**
 * Ký JWS tải trọng ra ngoài bằng khoá của cell (DV-13-02). Khoá riêng nằm ở OpenBao hoặc HSM, không
 * nằm trong cấu hình; bản hiện thực chỉ giữ tham chiếu.
 */
public interface PayloadSigner {

  /** Chữ ký dạng tách {@code <header>..<chữ ký>} (RFC 7515 phụ lục F). */
  String signDetached(byte[] payload);

  /** Chuỗi JWS dạng gọn bao lấy tải trọng. */
  String signCompact(byte[] payload);

  /** Bộ khoá công khai JWKS (RFC 7517) để đối tác kiểm chữ ký, gồm cả khoá cũ còn hiệu lực. */
  String publicJwks();
}
