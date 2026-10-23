package vn.viettel.okrkpi.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Lõi không chứa hằng nhận diện một tập khách hàng cụ thể (CORE-05-12, BS-03 ADR-33 "lõi không biết
 * hồ sơ nào"): mọi giá trị riêng nằm trong dữ liệu hồ sơ và các thành phần được trỏ tới. Quét toàn
 * bộ mã nguồn chính của mission-core; chú thích tham chiếu tài liệu cũng không được nêu tên hồ sơ.
 */
class CustomerProfileNeutralityTest {

  private static final Path MAIN = Path.of("src/main/java");

  /** Mã, viết tắt và thẻ của các tập khách hàng đã biết; thêm khi có hồ sơ mới. */
  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\btwd\\b"),
          Pattern.compile("\\bueb\\b"),
          Pattern.compile("ban-to-chuc"),
          Pattern.compile("khoi-dang-tw"),
          Pattern.compile("twd[/_-]"),
          Pattern.compile("[/_-]ueb\\b"));

  @Test
  void maNguonChinhKhongCoHangNhanDienHoSoKhachHang() throws IOException {
    assertThat(MAIN).isDirectory();
    try (Stream<Path> files = Files.walk(MAIN)) {
      List<String> hits =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .flatMap(CustomerProfileNeutralityTest::hits)
              .toList();
      assertThat(hits).as("hằng nhận diện hồ sơ khách hàng trong lõi").isEmpty();
    }
  }

  private static Stream<String> hits(Path file) {
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      return java.util.stream.IntStream.range(0, lines.size())
          .filter(
              i -> {
                String line = lines.get(i).toLowerCase(Locale.ROOT);
                return FORBIDDEN.stream().anyMatch(p -> p.matcher(line).find());
              })
          .mapToObj(i -> MAIN.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).strip());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
