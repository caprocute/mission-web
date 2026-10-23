package vn.viettel.okrkpi.integ.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Danh sách tệp hợp đồng trong {@code src/test/resources/pacts} (đã chép vào test-classes). */
final class PactFiles {

  private PactFiles() {}

  static Path dir() {
    try {
      return Path.of(PactFiles.class.getResource("/pacts").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  static List<String> names() {
    try (Stream<Path> s = Files.list(dir())) {
      return s.map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".json"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
