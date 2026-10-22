package vn.viettel.okrkpi.task.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItemDraft;
import vn.viettel.okrkpi.task.domain.catalog.CatalogRules;
import vn.viettel.okrkpi.task.domain.catalog.CatalogSheet;
import vn.viettel.okrkpi.task.domain.catalog.CatalogViolationException;
import vn.viettel.okrkpi.task.infrastructure.persistence.FastExcelSpreadsheets;

/**
 * Danh mục công việc mẫu theo khối ({@code data/seed/catalog/danh-muc-mau-K*.xlsx}, DL-06) đọc bằng
 * đúng bộ đọc FastExcel và quy tắc mà {@code importCatalogExcel} chạy, nên tệp lỗi của lần nhập là
 * rỗng (BR-04-14, BR-04-03). Bỏ qua khi không có thư mục seed.
 */
class CatalogSeedTest {

  private static final Path SEED = Path.of("../../data/seed/catalog");

  @Test
  void everyBlockFileImportsWithoutErrors() throws Exception {
    assumeTrue(Files.isDirectory(SEED), "không có thư mục seed");
    List<Path> files;
    try (Stream<Path> s = Files.list(SEED)) {
      files =
          s.filter(p -> p.getFileName().toString().matches("danh-muc-mau-K\\d\\.xlsx"))
              .sorted()
              .toList();
    }
    assertThat(files).hasSize(9);
    List<String> vague = CatalogPolicy.defaults().vagueWords(null);
    FastExcelSpreadsheets sheets = new FastExcelSpreadsheets();
    for (Path f : files) {
      List<String> errors = new ArrayList<>();
      int[] rows = {0};
      CatalogSheet.Columns[] columns = {null};
      sheets.read(
          f,
          (n, cells) -> {
            if (columns[0] == null) {
              columns[0] = CatalogSheet.columns(cells);
              assertThat(columns[0].missingRequired()).as(f.toString()).isEmpty();
              return true;
            }
            if (CatalogSheet.isBlank(cells)) {
              return true;
            }
            rows[0]++;
            CatalogSheet.Parsed parsed = CatalogSheet.parse(cells, columns[0]);
            parsed.violations().forEach(v -> errors.add(n + ": " + v.message()));
            CatalogItemDraft draft = CatalogRules.normalize(parsed.draft());
            try {
              CatalogRules.validate(draft, vague);
            } catch (CatalogViolationException e) {
              e.violations().forEach(v -> errors.add(n + ": " + v.message()));
            }
            assertThat(draft.code()).as(f + " dòng " + n).startsWith("TC-K");
            return true;
          });
      assertThat(errors).as(f.toString()).isEmpty();
      assertThat(rows[0]).as(f.toString()).isGreaterThanOrEqualTo(5);
    }
  }
}
