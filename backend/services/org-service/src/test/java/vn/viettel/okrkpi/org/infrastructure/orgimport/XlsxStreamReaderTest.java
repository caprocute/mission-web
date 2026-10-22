package vn.viettel.okrkpi.org.infrastructure.orgimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.application.orgimport.ImportColumns;
import vn.viettel.okrkpi.org.application.port.SpreadsheetReader.InvalidSpreadsheetException;
import vn.viettel.okrkpi.org.support.XlsxFixture;

/** DV-04-05: đọc .xlsx theo luồng bằng thư viện chuẩn của JDK. */
class XlsxStreamReaderTest {

  private final XlsxStreamReader reader = new XlsxStreamReader();

  @Test
  void readsSeedTemplateHeaderFromSharedStrings() throws IOException {
    byte[] template = Files.readAllBytes(Path.of("../../../data/seed/org/mau-nhap-cay-to-chuc.xlsx"));
    List<List<String>> rows = new ArrayList<>();
    reader.read(template, (n, cells) -> rows.add(cells));
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst()).containsExactlyElementsOf(ImportColumns.ALL);
  }

  @Test
  void keepsColumnPositionsForSparseRows() {
    byte[] xlsx = XlsxFixture.of(List.of(List.of("A", "", "C"), List.of("", "46023")));
    List<Integer> numbers = new ArrayList<>();
    List<List<String>> rows = new ArrayList<>();
    reader.read(xlsx, (n, cells) -> {
      numbers.add(n);
      rows.add(cells);
    });
    assertThat(numbers).containsExactly(1, 2);
    assertThat(rows.get(0)).containsExactly("A", "", "C");
    assertThat(rows.get(1)).containsExactly("", "46023");
    assertThat(XlsxStreamReader.columnIndex("AA7")).isEqualTo(26);
  }

  @Test
  void rejectsNonSpreadsheetAndExternalEntities() {
    assertThatThrownBy(() -> reader.read("PK not a zip".getBytes(), (n, c) -> { }))
        .isInstanceOf(InvalidSpreadsheetException.class);
    String evil = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
        + "<worksheet><sheetData><row r=\"1\"><c t=\"inlineStr\"><is><t>&e;</t></is></c></row></sheetData>"
        + "</worksheet>";
    byte[] zipped = zip(evil);
    assertThatThrownBy(() -> reader.read(zipped, (n, c) -> { })).isInstanceOf(InvalidSpreadsheetException.class);
  }

  private static byte[] zip(String sheet) {
    try (var out = new java.io.ByteArrayOutputStream(); var zip = new java.util.zip.ZipOutputStream(out)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
      zip.write(sheet.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.finish();
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
