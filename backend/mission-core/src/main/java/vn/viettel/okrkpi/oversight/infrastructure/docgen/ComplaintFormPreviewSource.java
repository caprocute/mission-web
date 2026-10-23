package vn.viettel.okrkpi.oversight.infrastructure.docgen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintTarget;
import vn.viettel.okrkpi.shared.docgen.FormPreviewSource;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dữ liệu xem trước văn bản trả lời khiếu nại (SCR-1202, QT-12 bước 5) cho SVC-11 qua cổng xem
 * trước của MOD-06: các trường của khiếu nại và bước tiếp nhận, không gồm nội dung và đề nghị (C2).
 */
public class ComplaintFormPreviewSource implements FormPreviewSource {

  static final String MODULE = "oversight";

  private static final ObjectMapper CANONICAL =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  private final ComplaintService complaints;

  public ComplaintFormPreviewSource(ComplaintService complaints) {
    this.complaints = Objects.requireNonNull(complaints, "complaints");
  }

  @Override
  public String module() {
    return MODULE;
  }

  @Override
  public Optional<Source> find(TenantId tenant, UUID actor, UUID sourceRefId) {
    return complaints
        .forDocument(tenant, actor, sourceRefId)
        .map(c -> new Source(c.profileId(), json(c)));
  }

  static byte[] json(Complaint c) {
    Map<String, Object> complaint = new TreeMap<>();
    put(complaint, "id", c.id());
    put(complaint, "code", c.code());
    put(complaint, "periodId", c.periodId());
    put(complaint, "profileId", c.profileId());
    put(complaint, "complainantId", c.complainantId());
    put(complaint, "kind", c.kind().name());
    put(complaint, "targetStage", c.targetStage().name());
    put(complaint, "subjectLine", c.subjectLine());
    put(complaint, "reserveOpinion", c.reserveOpinion());
    put(complaint, "status", c.status() == null ? null : c.status().name());
    put(complaint, "filedAt", c.filedAt());
    put(complaint, "responseDeadline", c.responseDeadline());
    put(complaint, "handlerUnitId", c.handlerUnitId());
    put(complaint, "handlerId", c.handling().handlerId());
    put(complaint, "acceptedAt", c.handling().acceptedAt());
    List<Map<String, Object>> targets = new ArrayList<>();
    for (ComplaintTarget t : c.targets()) {
      Map<String, Object> m = new TreeMap<>();
      put(m, "key", t.key());
      put(m, "stage", t.stage().name());
      put(m, "label", t.label());
      targets.add(m);
    }
    complaint.put("targets", targets);
    try {
      return CANONICAL.writeValueAsBytes(Map.of("complaint", complaint));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void put(Map<String, Object> m, String key, Object value) {
    if (value != null) {
      m.put(key, value instanceof String || value instanceof Boolean ? value : value.toString());
    }
  }
}
