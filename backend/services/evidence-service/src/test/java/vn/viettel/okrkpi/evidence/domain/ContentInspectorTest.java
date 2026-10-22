package vn.viettel.okrkpi.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.evidence.domain.ContentInspector.Inspection;

class ContentInspectorTest {

  @Test
  void bamVaKichThuocTinhMotLuot() throws Exception {
    byte[] data = "%PDF-1.7 nội dung".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Inspection i = inspect(data);
    assertThat(i.size()).isEqualTo(data.length);
    assertThat(i.sha256()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(data));
    assertThat(i.detected()).isEqualTo(FileKind.PDF);
    assertThat(i.matches(FileKind.PDF)).isTrue();
    assertThat(i.matches(FileKind.PNG)).isFalse();
  }

  @Test
  void nhanAnhTheoChuKy() throws Exception {
    assertThat(inspect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0}).detected())
        .isEqualTo(FileKind.PNG);
    assertThat(inspect(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}).detected())
        .isEqualTo(FileKind.JPEG);
    assertThat(inspect(new byte[] {'M', 'Z', 0}).detected()).isNull();
    assertThat(inspect(new byte[] {1}).detected()).isNull();
  }

  @Test
  void goiOfficeVaMacro() throws Exception {
    assertThat(inspect(zip("word/document.xml")).detected()).isEqualTo(FileKind.DOCX);
    assertThat(inspect(zip("xl/workbook.xml")).detected()).isEqualTo(FileKind.XLSX);
    Inspection macro = inspect(zip("word/document.xml", "word/vbaProject.bin"));
    assertThat(macro.hasMacro()).isTrue();
    assertThat(macro.matches(FileKind.DOCX)).isFalse();
    assertThat(inspect(zip("noi-dung.txt")).detected()).isNull();
    byte[] broken = zip("word/document.xml");
    broken[10] ^= 0x7f;
    broken[20] ^= 0x7f;
    assertThat(inspect(java.util.Arrays.copyOf(broken, 40)).detected()).isNull();
  }

  @Test
  void banMaKhongNhanKieu() throws Exception {
    byte[] data = "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    Inspection i = ContentInspector.inspect(new ByteArrayInputStream(data), false);
    assertThat(i.detected()).isNull();
    assertThat(i.size()).isEqualTo(data.length);
  }

  private static Inspection inspect(byte[] data) throws IOException {
    return ContentInspector.inspect(new ByteArrayInputStream(data), true);
  }

  private static byte[] zip(String... names) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(out)) {
      for (String n : names) {
        z.putNextEntry(new ZipEntry(n));
        z.write("<x/>".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        z.closeEntry();
      }
    }
    return out.toByteArray();
  }
}
