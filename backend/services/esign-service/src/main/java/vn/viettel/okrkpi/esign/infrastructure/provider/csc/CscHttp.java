package vn.viettel.okrkpi.esign.infrastructure.provider.csc;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * Client HTTP tới máy chủ CSC với thời gian chờ đọc riêng (DV-10-06). Thời gian chờ đọc mặc định
 * của nền tảng (3 giây) ngắn hơn thời hạn 10 giây của instance {@code esign-*} theo TL-01, nên một
 * lần ký chậm 3 giây bị cắt giữa chừng sau khi nhà cung cấp đã ký và lần thử lại ký thêm một chữ
 * ký.
 */
public final class CscHttp {

  private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

  private CscHttp() {}

  public static ResilientClient client(
      ResilientClientFactory factory,
      ObjectProvider<RestClient.Builder> builders,
      String instance,
      String baseUrl,
      Duration readTimeout) {
    JdkClientHttpRequestFactory requests =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    requests.setReadTimeout(readTimeout);
    RestClient.Builder builder = builders.getIfAvailable(RestClient::builder).clone();
    return factory.create(instance, builder.requestFactory(requests).baseUrl(baseUrl).build());
  }
}
