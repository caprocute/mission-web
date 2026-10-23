package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình gói chuyển liên cell {@code okrkpi.integ.cell-transfer} (DV-13-10). Khoá riêng, khoá gốc
 * và thông tin truy cập kho đối tượng chỉ là đường dẫn tệp hoặc biến môi trường do OpenBao Agent
 * cấp, không ghi giá trị vào cấu hình.
 */
@ConfigurationProperties("okrkpi.integ.cell-transfer")
public record CellTransferProperties(
    boolean enabled,
    String cellCode,
    String signingKeyPath,
    String decryptionKeyPath,
    Directory directory,
    Storage storage,
    Map<String, HttpTransferParts.Kind> parts,
    Token token,
    Duration timeout,
    Long maxBytes,
    Dispatch dispatch) {

  /** Danh bạ khoá cell có ký: vị trí tài liệu (file:, classpath:, https:) và khoá gốc. */
  public record Directory(
      String location, String rootKeyPath, Duration refresh, boolean allowHttp) {
    public Directory {
      refresh = refresh == null ? Duration.ofMinutes(5) : refresh;
    }
  }

  /** Bucket {@code transfer}; {@code retention} 0 thì không đặt khoá giữ. */
  public record Storage(
      String endpoint,
      String region,
      String bucket,
      String accessKey,
      String secretKey,
      Duration retention) {
    public Storage {
      region = region == null || region.isBlank() ? "us-east-1" : region;
      bucket = bucket == null || bucket.isBlank() ? "transfer" : bucket;
      retention = retention == null ? Duration.ofDays(365) : retention;
    }
  }

  /** Token dịch vụ gọi cổng nội bộ thu thập và nhập. */
  public record Token(String tokenUrl, String clientId, String clientSecret) {}

  /** Worker gửi, nhập, báo kết quả. */
  public record Dispatch(Boolean enabled, Duration period, Integer batch) {
    public Dispatch {
      enabled = enabled == null || enabled;
      period = period == null ? Duration.ofSeconds(10) : period;
      batch = batch == null ? 20 : batch;
    }
  }

  public CellTransferProperties {
    directory = directory == null ? new Directory(null, null, null, false) : directory;
    storage = storage == null ? new Storage(null, null, null, null, null, null) : storage;
    parts = parts == null ? Map.of() : Map.copyOf(parts);
    token = token == null ? new Token(null, null, null) : token;
    timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    maxBytes = maxBytes == null ? 50L * 1024 * 1024 : maxBytes;
    dispatch = dispatch == null ? new Dispatch(null, null, null) : dispatch;
  }
}
