package vn.viettel.okrkpi.policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Đọc Policy Pack giả lập trong {@code data/policy-pack/} của repo để kiểm thử nạp (DL-01, DL-03,
 * DL-04). Tệp dữ liệu là nguồn duy nhất: kiểm thử đọc thẳng thư mục repo, không chép sang {@code
 * src/test/resources}.
 */
final class PolicyPackFixtures {

  static final String GOI_DANG = "khoi-dang/quy-dinh-moi";
  static final String GOI_NHA_NUOC = "khoi-chinh-quyen/nd335";

  private PolicyPackFixtures() {}

  /** Thư mục gốc repo: đi ngược từ thư mục làm việc tới chỗ có {@code data/policy-pack}. */
  static Path repoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null) {
      if (Files.isDirectory(p.resolve("data/policy-pack"))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException("Không tìm thấy thư mục data/policy-pack từ thư mục làm việc");
  }

  static Path pack(String id) {
    return repoRoot().resolve("data/policy-pack").resolve(id);
  }

  @SuppressWarnings("unchecked")
  static <T> T yaml(Path file) {
    // SafeConstructor: chỉ dựng map, list, giá trị đơn; ngày dạng 2027-Q1 vẫn là chuỗi.
    Yaml y = new Yaml(new SafeConstructor(new LoaderOptions()));
    try (InputStream in = Files.newInputStream(file)) {
      return (T) y.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<Path> files(Path dir, String glob) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(f -> f.getFileSystem().getPathMatcher("glob:" + glob).matches(f.getFileName()))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Tên realm role trong ma trận phân quyền (docs/phan-quyen/ma-tran.json, AC-01..AC-12). */
  @SuppressWarnings("unchecked")
  static Set<String> realmRoles() {
    Map<String, Object> m = yaml(repoRoot().resolve("docs/phan-quyen/ma-tran.json"));
    return ((List<Map<String, Object>>) m.get("vai_tro"))
        .stream().map(r -> (String) r.get("role")).collect(Collectors.toUnmodifiableSet());
  }
}
