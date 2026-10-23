package vn.viettel.okrkpi.integ.infrastructure.cbccvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsBinding;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapper;
import vn.viettel.okrkpi.integ.application.outbound.OutboundPolicy;
import vn.viettel.okrkpi.integ.application.rating.RatingDecided;
import vn.viettel.okrkpi.integ.application.rating.RatingShareConverter;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.vtokr.appraisal.v1.XepLoaiDaQuyetDinh;

/**
 * EVT-12 {@code appraisal.rating.decided.v1} tới CSDL quốc gia CBCCVC (DV-13-09): giải mã Avro rồi
 * để {@link RatingShareConverter} đổi sang mã ngoài; phong bì, chính sách chia sẻ và chữ ký JWS
 * dùng khung DV-13-02.
 */
public class RatingDecidedBinding extends CloudEventsBinding {

  /** Loại thông điệp phía hệ ngoài. */
  public static final String MESSAGE_TYPE = "QUYET_DINH_XEP_LOAI";

  private final RatingShareConverter converter;

  public RatingDecidedBinding(
      CloudEventsMapper mapper, OutboundPolicy policy, RatingShareConverter converter) {
    super(mapper, policy);
    if (!policy.connectorCode().equals(converter.connectorCode())) {
      throw new IllegalArgumentException("Chính sách và bộ đổi khác mã kết nối");
    }
    this.converter = converter;
  }

  @Override
  public String topic() {
    return EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH;
  }

  @Override
  public String messageType() {
    return MESSAGE_TYPE;
  }

  @Override
  protected Converted convert(byte[] eventPayload, Map<String, String> headers) {
    String tenant = headers.get(OutboxHeaders.TENANT_ID);
    String eventId = headers.get(OutboxHeaders.EVENT_ID);
    if (tenant == null || eventId == null) {
      throw new IllegalArgumentException("Sự kiện thiếu header tenantid hoặc id");
    }
    return converter.convert(
        TenantId.parse(tenant), UUID.fromString(eventId), decode(eventPayload));
  }

  static RatingDecided decode(byte[] body) {
    EventContracts.trustContractPackages();
    try {
      XepLoaiDaQuyetDinh e =
          new SpecificDatumReader<>(XepLoaiDaQuyetDinh.class)
              .read(null, DecoderFactory.get().binaryDecoder(body, null));
      return new RatingDecided(
          e.getProfileId(),
          e.getPersonId(),
          e.getFlow(),
          e.getRatingCode(),
          e.getDeciderId(),
          e.getSignRequestId(),
          e.getAggregateVersion());
    } catch (IOException ex) {
      throw new UncheckedIOException("Không giải mã được EVT-12", ex);
    }
  }
}
