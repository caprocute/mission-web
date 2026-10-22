package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình {@code okrkpi.esign.mysign} (DV-10-02). Địa chỉ CSC, client và khoá kho đối tượng là giá
 * trị triển khai (bí mật nạp từ OpenBao qua biến môi trường), không nằm trong mã; đổi từ máy chủ
 * CSC giả lập sang MySign thật chỉ đổi các giá trị này.
 *
 * @param baseUrl gốc CSC API, ví dụ {@code https://<máy chủ>/csc/v2}
 * @param tokenUrl điểm cấp token OAuth2 client credentials
 * @param paths đường dẫn từng thao tác CSC tính từ {@code baseUrl}
 * @param tsaUrl máy chủ dấu thời gian RFC 3161 cho PAdES-B-T; rỗng thì ký mức B-B (chờ DV-10-03)
 * @param signatureSize số byte dành cho CMS trong PDF (chuỗi chứng thư, dấu thời gian, OCSP)
 * @param tokenSkew làm mới token sớm hơn hạn chừng này
 */
@ConfigurationProperties(prefix = "okrkpi.esign.mysign")
public record MySignProperties(
    boolean enabled,
    String baseUrl,
    String tokenUrl,
    String clientId,
    String clientSecret,
    String scope,
    Paths paths,
    String tsaUrl,
    Storage storage,
    Integer signatureSize,
    Duration tokenSkew) {

  public MySignProperties {
    paths = paths == null ? new Paths(null, null, null, null) : paths;
    storage = storage == null ? new Storage(null, null, null, null, null, null) : storage;
    signatureSize = signatureSize == null ? 32_768 : signatureSize;
    tokenSkew = tokenSkew == null ? Duration.ofSeconds(30) : tokenSkew;
  }

  /** Đường dẫn CSC API v2 (mặc định theo tên thao tác của chuẩn). */
  public record Paths(
      String credentialsList, String credentialsInfo, String signHash, String signPolling) {
    public Paths {
      credentialsList = credentialsList == null ? "/credentials/list" : credentialsList;
      credentialsInfo = credentialsInfo == null ? "/credentials/info" : credentialsInfo;
      signHash = signHash == null ? "/signatures/signHash" : signHash;
      signPolling = signPolling == null ? "/signatures/signPolling" : signPolling;
    }
  }

  /**
   * Kho đối tượng S3 (Ceph RGW) chứa PDF/A cần ký ({@code document_ref} là khoá đối tượng) và nơi
   * ghi tệp đã ký; tệp trung gian của phiên nằm dưới tiền tố {@code tam/} (vòng đời bucket xoá sau
   * 1 ngày, suy luận).
   */
  public record Storage(
      String endpoint,
      String region,
      String bucket,
      String accessKey,
      String secretKey,
      String signedPrefix) {
    public Storage {
      region = region == null ? "us-east-1" : region;
      bucket = bucket == null ? "dossier" : bucket;
      signedPrefix = signedPrefix == null ? "ky-so" : signedPrefix;
    }
  }
}
