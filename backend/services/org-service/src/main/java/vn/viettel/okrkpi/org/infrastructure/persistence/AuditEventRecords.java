package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.vtokr.sys.v1.AuditEvent;
import vn.vtokr.sys.v1.AuditTarget;

/**
 * Dựng bản ghi Avro {@code AuditEvent} của {@code sys.audit.v1} (contracts/events/avro/sys/AuditEvent.avsc) từ thân sự
 * kiện dạng map mà tầng ứng dụng ghi vào outbox. Khoá lạ bị từ chối để thân không lệch khỏi hợp đồng.
 */
final class AuditEventRecords {

  private static final Set<String> FIELDS = Set.of("action", "objectType", "objectId", "outcome", "purpose",
      "actorAssignmentId", "actorRole", "system", "targets");

  private AuditEventRecords() {
  }

  static AuditEvent of(Map<String, Object> payload) {
    for (String key : payload.keySet()) {
      if (!FIELDS.contains(key)) {
        throw new IllegalArgumentException("Trường " + key + " không có trong lược đồ sys.audit.v1");
      }
    }
    Object assignment = payload.get("actorAssignmentId");
    return AuditEvent.newBuilder()
        .setAction((String) payload.get("action"))
        .setObjectType((String) payload.get("objectType"))
        .setObjectId((String) payload.get("objectId"))
        .setOutcome((String) payload.get("outcome"))
        .setPurpose((String) payload.get("purpose"))
        .setActorAssignmentId(assignment == null ? null : UUID.fromString(assignment.toString()))
        .setActorRole((String) payload.get("actorRole"))
        .setSystem(Boolean.TRUE.equals(payload.get("system")))
        .setTargets(targets(payload.get("targets")))
        .build();
  }

  @SuppressWarnings("unchecked")
  private static List<AuditTarget> targets(Object raw) {
    if (raw == null) {
      return null;
    }
    List<AuditTarget> out = new ArrayList<>();
    for (Object o : (List<Object>) raw) {
      Map<String, Object> t = (Map<String, Object>) o;
      out.add(AuditTarget.newBuilder()
          .setObjectType((String) t.get("objectType"))
          .setObjectId((String) t.get("objectId"))
          .setColumn((String) t.get("column"))
          .setDekPurpose((String) t.get("dekPurpose"))
          .setSelf((Boolean) t.get("self"))
          .build());
    }
    return out;
  }
}
