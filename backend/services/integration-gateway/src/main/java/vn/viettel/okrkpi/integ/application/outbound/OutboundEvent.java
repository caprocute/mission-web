package vn.viettel.okrkpi.integ.application.outbound;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.Classification;

/**
 * Một sự kiện nội bộ đã được bộ kết nối chuyển sang mã ngoài, sẵn sàng dựng phong bì CloudEvents
 * 1.0 gửi ra hệ ngoài (DV-13-02, TL-01 "Phong bì sự kiện").
 *
 * @param eventId mã sự kiện nội bộ (UUIDv7, tiêu đề {@code id}), giữ nguyên làm {@code id} ra ngoài
 *     để bên nhận khử trùng
 * @param topic topic nội bộ, ví dụ {@code appraisal.rating.decided.v1}
 * @param subject khoá thực thể chính, ví dụ {@code appraisal-profile/<id>}
 * @param time thời điểm phát sinh
 * @param traceparent W3C Trace Context; rỗng thì bỏ thuộc tính
 * @param tenantId tenant sở hữu sự kiện
 * @param classification nhãn phân loại của sự kiện (tiêu đề {@code classification})
 * @param policyVersion phiên bản Policy Pack (tiêu đề {@code policyversion}); rỗng thì bỏ
 * @param data dữ liệu nghiệp vụ đã dùng mã ngoài
 * @param dataClassification mức cao nhất của dữ liệu trong {@code data} do bộ kết nối khai báo
 */
public record OutboundEvent(
    UUID eventId,
    String topic,
    String subject,
    Instant time,
    String traceparent,
    UUID tenantId,
    Classification classification,
    String policyVersion,
    ObjectNode data,
    Classification dataClassification) {

  public OutboundEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(data, "data");
    classification = classification == null ? Classification.C1 : classification;
    dataClassification = dataClassification == null ? classification : dataClassification;
  }

  /** Mức phân loại thực tế: mức cao hơn giữa nhãn sự kiện và dữ liệu. */
  public Classification effectiveClassification() {
    return dataClassification.isAtLeast(classification) ? dataClassification : classification;
  }

  /** Phần dữ liệu không in ra khi ghi nhật ký. */
  @Override
  public String toString() {
    return "OutboundEvent[" + eventId + ", " + topic + ", " + effectiveClassification() + "]";
  }
}
