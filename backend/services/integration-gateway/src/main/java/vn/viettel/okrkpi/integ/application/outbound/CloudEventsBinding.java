package vn.viettel.okrkpi.integ.application.outbound;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.OutboundBinding;
import vn.viettel.okrkpi.shared.kernel.Classification;

/**
 * Khung {@link OutboundBinding} cho mọi luồng ra dùng CloudEvents (DV-13-02): bộ kết nối cụ thể
 * (DV-13-06 EVT-12, DV-13-09 bản neo) chỉ đổi tải trọng Avro nội bộ sang dữ liệu mã ngoài; dựng
 * phong bì và kiểm chính sách chia sẻ do {@link CloudEventsMapper} làm chung.
 */
public abstract class CloudEventsBinding implements OutboundBinding {

  /** Dữ liệu đã đổi sang mã ngoài và mức phân loại cao nhất của nó. */
  public record Converted(String subject, ObjectNode data, Classification dataClassification) {
    public Converted {
      Objects.requireNonNull(data, "data");
    }
  }

  private final CloudEventsMapper mapper;
  private final OutboundPolicy policy;

  protected CloudEventsBinding(CloudEventsMapper mapper, OutboundPolicy policy) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public String connectorCode() {
    return policy.connectorCode();
  }

  /** Đổi tải trọng nội bộ sang dữ liệu mã ngoài. */
  protected abstract Converted convert(byte[] eventPayload, Map<String, String> headers);

  @Override
  public byte[] format(byte[] eventPayload, Map<String, String> headers) {
    Converted c = convert(eventPayload, headers);
    return mapper.toJson(
        mapper.fromHeaders(topic(), headers, c.subject(), c.data(), c.dataClassification()),
        policy);
  }
}
