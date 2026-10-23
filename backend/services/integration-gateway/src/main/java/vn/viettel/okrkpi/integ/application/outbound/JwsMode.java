package vn.viettel.okrkpi.integ.application.outbound;

/**
 * Cách đính chữ ký JWS vào thông điệp ra theo yêu cầu bên nhận (DV-13-02).
 *
 * <ul>
 *   <li>{@link #DETACHED}: thân là phong bì CloudEvents JSON nguyên văn, chữ ký dạng tách (RFC 7515
 *       phụ lục F, {@code <header>..<chữ ký>}) đặt ở tiêu đề {@code X-JWS-Signature}.
 *   <li>{@link #COMPACT}: thân là chuỗi JWS dạng gọn bao lấy phong bì, kiểu {@code
 *       application/jose}.
 * </ul>
 */
public enum JwsMode {
  DETACHED,
  COMPACT
}
