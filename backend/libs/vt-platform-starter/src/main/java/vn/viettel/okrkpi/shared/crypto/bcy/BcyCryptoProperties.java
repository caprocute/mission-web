package vn.viettel.okrkpi.shared.crypto.bcy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình chế độ mật mã Ban Cơ yếu {@code okrkpi.crypto.bcy} (DV-10-05). Chỉ dùng khi {@code
 * okrkpi.crypto.provider=bcy}. Không có thuộc tính nhận mã PIN dạng rõ: tham số {@code params} chỉ
 * chứa tên khe khoá và đường dẫn tệp do Secret gắn vào pod (ví dụ {@code pin-file}).
 *
 * @param libraryPath các jar hoặc thư mục của thư viện (và lớp bọc); rỗng là đã có trên classpath
 * @param libraryClass tên lớp hiện thực {@link BcyCryptoLibrary}
 * @param params tham số nạp truyền nguyên cho {@link BcyCryptoLibrary#open(Map)}
 * @param allowedAlgorithms danh sách mã thuật toán được phép; mọi thuật toán thư viện khai phải
 *     thuộc danh sách này
 */
@ConfigurationProperties(prefix = "okrkpi.crypto.bcy")
public record BcyCryptoProperties(
    List<Path> libraryPath,
    String libraryClass,
    Map<String, String> params,
    Set<String> allowedAlgorithms) {

  public BcyCryptoProperties {
    libraryPath = libraryPath == null ? List.of() : List.copyOf(libraryPath);
    params = params == null ? Map.of() : Map.copyOf(params);
    allowedAlgorithms = allowedAlgorithms == null ? Set.of() : Set.copyOf(allowedAlgorithms);
  }
}
