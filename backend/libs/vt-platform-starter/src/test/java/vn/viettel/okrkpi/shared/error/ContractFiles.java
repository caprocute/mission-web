package vn.viettel.okrkpi.shared.error;

import java.nio.file.Files;
import java.nio.file.Path;

/** Tìm thư mục {@code contracts/openapi} của repo từ thư mục chạy kiểm thử (thư mục mô-đun). */
public final class ContractFiles {

  private ContractFiles() {}

  public static Path openapiDir() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("contracts").resolve("openapi");
      if (Files.isDirectory(candidate.resolve("common"))) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "Không tìm thấy contracts/openapi; kiểm thử hợp đồng phải chạy trong repo mission-web");
  }

  public static Path commonYaml() {
    return openapiDir().resolve("common").resolve("common.yaml");
  }
}
