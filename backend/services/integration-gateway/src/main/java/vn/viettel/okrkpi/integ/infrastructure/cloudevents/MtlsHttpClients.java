package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLParameters;

/**
 * Máy khách HTTP mTLS cho từng bộ kết nối chiều ra (DV-13-02): chứng thư tổ chức ở {@code
 * <tls-dir>/<mã kết nối viết thường>/} ({@link PemTls}). Máy khách được giữ lại và dựng lại khi
 * cert-manager gia hạn chứng thư (đổi thời điểm sửa tệp). Chỉ TLS 1.3 (AsyncAPI: mTLS 1.3).
 */
public class MtlsHttpClients {

  private record Entry(List<FileTime> stamps, HttpClient client) {}

  private final Path root;
  private final Duration connectTimeout;
  private final Map<String, Entry> clients = new ConcurrentHashMap<>();

  public MtlsHttpClients(Path root, Duration connectTimeout) {
    this.root = root == null ? null : root.toAbsolutePath().normalize();
    this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
  }

  /** Thư mục chứng thư của kết nối. */
  Path dir(String connectorCode) {
    if (root == null) {
      throw new IllegalStateException("Chưa cấu hình thư mục chứng thư mTLS");
    }
    Path dir = root.resolve(connectorCode.toLowerCase(Locale.ROOT)).normalize();
    if (!dir.startsWith(root)) {
      throw new IllegalArgumentException("Mã kết nối không hợp lệ");
    }
    return dir;
  }

  /** Máy khách mTLS của kết nối; thiếu tệp chứng thư thì báo lỗi, không lùi về kết nối thường. */
  public HttpClient client(String connectorCode) throws IOException {
    Path dir = dir(connectorCode);
    List<FileTime> stamps =
        List.of(
            Files.getLastModifiedTime(dir.resolve(PemTls.CERT)),
            Files.getLastModifiedTime(dir.resolve(PemTls.KEY)),
            Files.getLastModifiedTime(dir.resolve(PemTls.CA)));
    Entry e = clients.get(connectorCode);
    if (e != null && e.stamps().equals(stamps)) {
      return e.client();
    }
    SSLParameters params = new SSLParameters();
    params.setProtocols(new String[] {"TLSv1.3"});
    params.setEndpointIdentificationAlgorithm("HTTPS");
    HttpClient client =
        HttpClient.newBuilder()
            .sslContext(PemTls.context(dir))
            .sslParameters(params)
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    clients.put(connectorCode, new Entry(stamps, client));
    return client;
  }
}
