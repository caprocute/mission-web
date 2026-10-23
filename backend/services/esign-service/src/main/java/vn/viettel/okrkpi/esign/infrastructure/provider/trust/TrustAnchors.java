package vn.viettel.okrkpi.esign.infrastructure.provider.trust;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Nạp chứng thư gốc tin cậy của một nhà cung cấp từ cấu hình: mỗi phần tử là đường dẫn tệp PEM (một
 * tệp có thể chứa nhiều chứng thư) hoặc chính nội dung PEM. Ở Kubernetes, tệp gốc gắn từ ConfigMap
 * hay Secret; cập nhật kho gốc là đổi tệp rồi khởi động lại, không đổi mã.
 */
public final class TrustAnchors {

  private TrustAnchors() {}

  public static List<X509Certificate> load(List<String> sources) {
    List<X509Certificate> out = new ArrayList<>();
    if (sources == null) {
      return out;
    }
    for (String source : sources) {
      if (source == null || source.isBlank()) {
        continue;
      }
      byte[] pem;
      try {
        pem =
            source.contains("-----BEGIN")
                ? source.getBytes(StandardCharsets.US_ASCII)
                : Files.readAllBytes(Path.of(source.trim()));
      } catch (IOException e) {
        throw new IllegalStateException("Không đọc được tệp chứng thư gốc " + source, e);
      }
      try {
        for (var c :
            CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem))) {
          out.add((X509Certificate) c);
        }
      } catch (CertificateException e) {
        throw new IllegalStateException("Chứng thư gốc không hợp lệ: " + source, e);
      }
    }
    return out;
  }
}
