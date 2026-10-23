package vn.viettel.okrkpi.integ.infrastructure.cbccvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.OutboundBinding;
import vn.viettel.okrkpi.integ.application.OutboundSender;
import vn.viettel.okrkpi.integ.application.outbound.ExternalRefs;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.rating.RatingShareConverter;
import vn.viettel.okrkpi.integ.application.rating.SubjectChains;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventsSenderFactory;

/**
 * Chia sẻ quyết định xếp loại (EVT-12) lên CSDL quốc gia CBCCVC (DV-13-09) khi {@code
 * okrkpi.integ.rating-share.enabled=true}. Dùng chung mã kết nối với đồng bộ CSDL CBCCVC (DV-13-06,
 * mặc định {@code CBCCVC}) để dùng lại ánh xạ mã cán bộ; địa chỉ nhận qua NDXP là {@code
 * connector.endpoint} chiều ra của kết nối đó.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "okrkpi.integ.rating-share", name = "enabled", havingValue = "true")
public class RatingShareConfiguration {

  @Bean
  SubjectChains integSubjectChains(JdbcClient jdbc) {
    return new JdbcSubjectChains(jdbc);
  }

  @Bean
  RatingShareConverter integRatingShareConverter(
      @Value("${okrkpi.integ.rating-share.connector-code:CBCCVC}") String code,
      Connectors connectors,
      ExternalRefs refs,
      MappingXrefService xrefs,
      SubjectChains chains,
      ObjectProvider<ObjectMapper> json,
      TransactionTemplate integTransactions,
      ObjectProvider<Clock> clock) {
    return new RatingShareConverter(
        code,
        connectors,
        refs,
        xrefs,
        chains,
        json.getIfAvailable(ObjectMapper::new),
        integTransactions,
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  OutboundBinding integRatingDecidedBinding(
      CloudEventsSenderFactory senders, RatingShareConverter converter) {
    return new RatingDecidedBinding(
        senders.mapper(), senders.policy(converter.connectorCode()), converter);
  }

  @Bean
  OutboundSender integRatingShareSender(
      CloudEventsSenderFactory senders, RatingShareConverter converter) {
    return senders.sender(converter.connectorCode());
  }
}
