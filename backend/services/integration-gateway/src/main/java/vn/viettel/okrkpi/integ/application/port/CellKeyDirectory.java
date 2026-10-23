package vn.viettel.okrkpi.integ.application.port;

import java.net.URI;
import java.security.PublicKey;
import java.util.Optional;

/**
 * Danh bạ khoá công khai của các cell, lấy từ vùng điều phối toàn cục và đã kiểm chuỗi tin cậy
 * (DV-13-10). Chỉ trả cell có khoá còn hiệu lực.
 */
public interface CellKeyDirectory {

  /** Khoá ký, khoá nhận gói và địa chỉ kênh liên cell của một cell. */
  record CellKeys(String cellCode, URI endpoint, PublicKey signingKey, PublicKey encryptionKey) {}

  Optional<CellKeys> find(String cellCode);
}
