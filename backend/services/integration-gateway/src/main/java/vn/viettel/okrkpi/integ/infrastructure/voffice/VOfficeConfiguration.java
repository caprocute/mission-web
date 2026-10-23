package vn.viettel.okrkpi.integ.infrastructure.voffice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;

/**
 * Bộ kết nối vOffice và hệ cùng giao thức (ĐHTN) theo danh sách mã {@code
 * okrkpi.integ.voffice.codes}: mỗi mã có một route kéo và một nguồn tra cứu văn bản đã ban hành.
 * Địa chỉ, bí mật, lịch bật tắt theo tenant nằm ở {@code integ.connector}, không ở cấu hình này.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "okrkpi.integ.voffice.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class VOfficeConfiguration {

  /** Danh sách bộ kết nối kiểu vOffice; mỗi phần tử đăng ký route kéo của mình. */
  public record VOfficeBeans(
      List<InboundConnector> connectors, List<IssuedDocumentSource> sources) {}

  @Bean
  VOfficeBeans integVOffice(
      ConnectorSecrets secrets,
      ObjectProvider<ObjectMapper> mapper,
      @Value("${okrkpi.integ.voffice.codes:VOFFICE}") List<String> codes,
      @Value("${okrkpi.integ.voffice.period:PT30S}") Duration period,
      @Value("${okrkpi.integ.voffice.request-timeout:PT5S}") Duration timeout) {
    ObjectMapper json = mapper.getIfAvailable(ObjectMapper::new);
    VOfficeClient client =
        new VOfficeClient(
            HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            json,
            secrets,
            timeout);
    VOfficeTranslator translator = new VOfficeTranslator(json);
    return new VOfficeBeans(
        codes.stream()
            .map(c -> (InboundConnector) new VOfficeConnector(c, client, period, translator))
            .toList(),
        codes.stream()
            .map(c -> (IssuedDocumentSource) new VOfficeIssuedDocuments(c, client))
            .toList());
  }
}
