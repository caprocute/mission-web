package vn.viettel.okrkpi.integ.application.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException.Reason;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đổi mã nội bộ sang tham chiếu mã ngoài {@code {"scheme": ..., "id": ...}} cho dữ liệu ra ngoài
 * (DV-13-02, TL-01 ví dụ EVT-12: {@code subjectRef.scheme=cbccvc}) qua {@code integ.mapping_xref}.
 * Không có ánh xạ thì từ chối gửi, không bao giờ thay bằng mã nội bộ.
 */
public class ExternalRefs {

  private final MappingXrefService xrefs;
  private final ObjectMapper json;

  public ExternalRefs(MappingXrefService xrefs, ObjectMapper json) {
    this.xrefs = Objects.requireNonNull(xrefs, "xrefs");
    this.json = Objects.requireNonNull(json, "json");
  }

  /** Tham chiếu mã ngoài của {@code internalId} theo ánh xạ có hiệu lực ngày {@code on}. */
  public ObjectNode require(
      TenantId tenant,
      UUID connectorId,
      EntityType type,
      UUID internalId,
      String scheme,
      LocalDate on) {
    if (scheme == null || scheme.isBlank()) {
      throw new IllegalArgumentException("Thiếu tên hệ mã ngoài");
    }
    String external =
        xrefs
            .externalOf(tenant, connectorId, type, internalId, on)
            .orElseThrow(
                () ->
                    new OutboundRejectedException(
                        Reason.UNMAPPED_REFERENCE,
                        "Chưa có mã ngoài loại " + type + " cho dữ liệu cần gửi"));
    ObjectNode ref = json.createObjectNode();
    ref.put("scheme", scheme);
    ref.put("id", external);
    return ref;
  }
}
