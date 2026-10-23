package vn.viettel.okrkpi.integ.infrastructure.secrets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;

/**
 * Đọc bí mật kết nối từ thư mục do OpenBao Agent hoặc CSI kết xuất ({@code
 * okrkpi.integ.secrets-dir}): tệp {@code <thư mục>/<secret_path>}. Đường dẫn chứa {@code ..} hay
 * tuyệt đối bị từ chối; chưa cấu hình thư mục thì coi như kết nối không cần mã thông báo (bản giả
 * lập).
 */
public class FileConnectorSecrets implements ConnectorSecrets {

  private final Path root;

  public FileConnectorSecrets(Path root) {
    this.root = root == null ? null : root.toAbsolutePath().normalize();
  }

  @Override
  public Optional<String> token(String secretPath) {
    if (root == null || secretPath == null || secretPath.isBlank()) {
      return Optional.empty();
    }
    Path file = root.resolve(secretPath).normalize();
    if (!file.startsWith(root) || Path.of(secretPath).isAbsolute()) {
      throw new IllegalArgumentException("Đường dẫn bí mật nằm ngoài thư mục cho phép");
    }
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      String v = Files.readString(file, StandardCharsets.UTF_8).trim();
      return v.isEmpty() ? Optional.empty() : Optional.of(v);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
