package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapper;
import vn.viettel.okrkpi.integ.application.outbound.OutboundPolicy;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.application.port.PayloadSigner;

/**
 * Lắp adapter chiều ra CloudEvents cho một mã kết nối (DV-13-02). Luồng cụ thể khai báo bean, ví dụ
 * {@code @Bean OutboundSender cbccvcSender(CloudEventsSenderFactory f) { return
 * f.sender("CSDL_CBCCVC"); }}, cùng một {@code CloudEventsBinding} dùng {@link #policy} và {@link
 * #mapper}.
 */
public class CloudEventsSenderFactory {

  private final CloudEventsProperties props;
  private final CloudEventsMapper mapper;
  private final PayloadSigner signer;
  private final MtlsHttpClients mtls;
  private final HttpClient plain;
  private final ConnectorSecrets secrets;
  private final ObjectMapper json;

  public CloudEventsSenderFactory(
      CloudEventsProperties props,
      CloudEventsMapper mapper,
      PayloadSigner signer,
      MtlsHttpClients mtls,
      HttpClient plain,
      ConnectorSecrets secrets,
      ObjectMapper json) {
    this.props = Objects.requireNonNull(props, "props");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.mtls = Objects.requireNonNull(mtls, "mtls");
    this.plain = Objects.requireNonNull(plain, "plain");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.json = Objects.requireNonNull(json, "json");
  }

  public OutboundPolicy policy(String connectorCode) {
    return props.policy(connectorCode);
  }

  public CloudEventsMapper mapper() {
    return mapper;
  }

  public CloudEventsHttpSender sender(String connectorCode) {
    return new CloudEventsHttpSender(
        policy(connectorCode), mapper, signer, mtls, plain, secrets, json, props.requestTimeout());
  }
}
