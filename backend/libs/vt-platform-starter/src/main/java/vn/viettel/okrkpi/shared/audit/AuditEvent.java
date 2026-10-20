package vn.viettel.okrkpi.shared.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Một sự kiện kiểm toán (TL-01 "Tách nhật ký kiểm toán"). Không mang dữ liệu C2/C3 dạng rõ: chỉ
 * định danh đối tượng và mức phân loại của dữ liệu đã truy cập. {@code eventId}, {@code
 * occurredAt}, {@code traceId} để trống thì {@link AuditLogger} điền.
 *
 * @param eventId mã sự kiện UUIDv7
 * @param occurredAt thời điểm (UTC)
 * @param type loại sự kiện
 * @param outcome kết quả
 * @param tenantId tenant, {@code null} với thao tác liên tenant của quản trị nền tảng
 * @param actorId người thực hiện
 * @param actorRole mã vai trò AC của người thực hiện trong phân công hiện hành, có thể {@code null}
 * @param action tên thao tác, ví dụ {@code GRANT_ROLE}
 * @param objectType loại đối tượng, có thể {@code null}
 * @param objectId mã đối tượng, có thể {@code null}
 * @param classification mức dữ liệu bị truy cập, bắt buộc với {@link
 *     AuditEventType#SENSITIVE_DATA_ACCESS}
 * @param traceId mã vết
 * @param attributes thuộc tính bổ sung (không chứa dữ liệu nhạy cảm; vẫn được che khi ghi)
 */
public record AuditEvent(
    UUID eventId,
    Instant occurredAt,
    AuditEventType type,
    AuditOutcome outcome,
    TenantId tenantId,
    ActorId actorId,
    String actorRole,
    String action,
    String objectType,
    String objectId,
    Classification classification,
    String traceId,
    Map<String, String> attributes) {

  public AuditEvent {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(action, "action");
    if (action.isBlank()) {
      throw new IllegalArgumentException("action rỗng");
    }
    if (type == AuditEventType.SENSITIVE_DATA_ACCESS
        && (classification == null || !classification.isSensitive())) {
      throw new IllegalArgumentException("Truy cập dữ liệu nhạy cảm phải ghi mức C2 hoặc C3");
    }
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public static Builder builder(AuditEventType type, ActorId actorId, String action) {
    return new Builder(type, actorId, action);
  }

  /** Bản sao đã điền mã sự kiện, thời điểm, mã vết và thuộc tính đã che. */
  AuditEvent complete(UUID id, Instant at, String trace, Map<String, String> attrs) {
    return new AuditEvent(
        eventId != null ? eventId : id,
        occurredAt != null ? occurredAt : at,
        type,
        outcome,
        tenantId,
        actorId,
        actorRole,
        action,
        objectType,
        objectId,
        classification,
        traceId != null ? traceId : trace,
        attrs);
  }

  /** Dựng sự kiện từng bước. */
  public static final class Builder {
    private final AuditEventType type;
    private final ActorId actorId;
    private final String action;
    private AuditOutcome outcome = AuditOutcome.SUCCESS;
    private TenantId tenantId;
    private String actorRole;
    private String objectType;
    private String objectId;
    private Classification classification;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private Builder(AuditEventType type, ActorId actorId, String action) {
      this.type = type;
      this.actorId = actorId;
      this.action = action;
    }

    public Builder outcome(AuditOutcome value) {
      this.outcome = value;
      return this;
    }

    public Builder tenant(TenantId value) {
      this.tenantId = value;
      return this;
    }

    public Builder actorRole(String value) {
      this.actorRole = value;
      return this;
    }

    public Builder object(String type, String id) {
      this.objectType = type;
      this.objectId = id;
      return this;
    }

    public Builder classification(Classification value) {
      this.classification = value;
      return this;
    }

    public Builder attribute(String key, String value) {
      this.attributes.put(Objects.requireNonNull(key, "key"), value == null ? "" : value);
      return this;
    }

    public AuditEvent build() {
      return new AuditEvent(
          null,
          null,
          type,
          outcome,
          tenantId,
          actorId,
          actorRole,
          action,
          objectType,
          objectId,
          classification,
          null,
          attributes);
    }
  }
}
