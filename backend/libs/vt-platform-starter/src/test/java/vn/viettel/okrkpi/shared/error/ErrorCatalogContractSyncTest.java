package vn.viettel.okrkpi.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Danh mục đóng gói phải khớp hợp đồng hiện hành: quét lại {@code contracts/openapi/**} độc lập với
 * script sinh và so tập mã. Lệch thì chạy {@code node
 * backend/libs/vt-platform-starter/tools/extract-error-codes.mjs}.
 */
class ErrorCatalogContractSyncTest {

  private static final Pattern CODE =
      Pattern.compile("\\b[A-Z][A-Z0-9]*-[1-5][0-9]{2}-[A-Z0-9]+(?:-[A-Z0-9]+)*\\b");

  @Test
  void danhMucKhopMaTrongHopDong() throws IOException {
    Set<String> inContracts = new TreeSet<>();
    try (Stream<Path> files = Files.walk(ContractFiles.openapiDir())) {
      for (Path f : files.filter(p -> p.toString().matches(".*\\.ya?ml$")).toList()) {
        Matcher m = CODE.matcher(Files.readString(f, StandardCharsets.UTF_8));
        while (m.find()) {
          inContracts.add(m.group());
        }
      }
    }
    Set<String> inCatalog = new TreeSet<>();
    ErrorCatalog.standard().all().forEach(c -> inCatalog.add(c.code()));

    assertThat(inCatalog)
        .as("error-codes.json lỗi thời so với contracts/openapi; chạy lại extract-error-codes.mjs")
        .isEqualTo(inContracts);
  }
}
