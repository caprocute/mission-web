package vn.viettel.okrkpi.integ.application.port;

import java.util.Optional;

/**
 * Bí mật của kết nối theo {@code integ.connector.secret_path} (OpenBao): mã thông báo gọi API đối
 * tác. Mã nguồn và bản ghi kết nối chỉ giữ đường dẫn, không giữ giá trị.
 */
public interface ConnectorSecrets {

  Optional<String> token(String secretPath);
}
