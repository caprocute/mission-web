package vn.viettel.okrkpi.shared.authz;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hợp đồng đầu vào gửi tới OPA {@code POST /v1/data/okrkpi/authz/decision} (NEN-014, NEN-040,
 * {@code backend/opa/README.md}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpaInput(
    @JsonProperty("subject") Subject subject,
    @JsonProperty("action") String action,
    @JsonProperty("request") Request request,
    @JsonProperty("resource") Resource resource,
    @JsonProperty("context") Context context) {

  public OpaInput {
    Objects.requireNonNull(subject, "subject must not be null");
    resource = resource == null ? Resource.empty() : resource;
  }

  /**
   * Khoá bộ đệm quyết định theo {@code (người dùng, hành động, yêu cầu API, tài nguyên, phiên bản
   * bundle)} (TL-01).
   */
  public String cacheKey(String bundleRevision) {
    return bundleRevision
        + "|"
        + subject
        + "|"
        + (action == null ? "" : action)
        + "|"
        + (request == null ? "" : request)
        + "|"
        + resource;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Subject(
      @JsonProperty("kind") String kind,
      @JsonProperty("user_id") String userId,
      @JsonProperty("client_id") String clientId,
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("unit_id") String unitId,
      @JsonProperty("roles") List<String> roles,
      @JsonProperty("acr") String acr,
      @JsonProperty("auth_time") Long authTime,
      @JsonProperty("flows") List<String> flows) {

    public Subject {
      kind = (kind == null || kind.isBlank()) ? "user" : kind;
      roles = roles == null ? List.of() : List.copyOf(roles);
      flows = flows == null ? List.of() : List.copyOf(flows);
    }

    public static Subject user(
        String userId,
        String tenantId,
        String unitId,
        List<String> roles,
        String acr,
        Long authTime,
        List<String> flows) {
      return new Subject("user", userId, null, tenantId, unitId, roles, acr, authTime, flows);
    }

    public static Subject client(String clientId, String tenantId) {
      return new Subject(
          "client", null, clientId, tenantId, null, List.of(), null, null, List.of());
    }
  }

  /**
   * Lớp API của quyết định. {@code operation} là mã thao tác OpenAPI: chủ thể client (AC-11) chỉ
   * qua với thao tác hợp đồng khai {@code service_client} ({@code
   * data.okrkpi.generated.client_operations}).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Request(
      @JsonProperty("module") String module,
      @JsonProperty("method") String method,
      @JsonProperty("operation") String operation) {

    public Request(String module, String method) {
      this(module, method, null);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Resource(
      @JsonProperty("type") String type,
      @JsonProperty("id") String id,
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("owner_unit_id") String ownerUnitId,
      @JsonProperty("owner_ids") List<String> ownerIds,
      @JsonProperty("assignee_ids") List<String> assigneeIds,
      @JsonProperty("designated_ids") List<String> designatedIds,
      @JsonProperty("delegate_ids") List<String> delegateIds,
      @JsonProperty("flow") String flow,
      @JsonProperty("classification") String classification,
      @JsonProperty("recipient_ids") List<String> recipientIds,
      @JsonProperty("participants") Map<String, List<String>> participants,
      @JsonProperty("small_unit_exception") Boolean smallUnitException,
      @JsonProperty("granted_role") String grantedRole,
      @JsonProperty("concluded") Boolean concluded) {

    public Resource {
      ownerIds = ownerIds == null ? null : List.copyOf(ownerIds);
      assigneeIds = assigneeIds == null ? null : List.copyOf(assigneeIds);
      designatedIds = designatedIds == null ? null : List.copyOf(designatedIds);
      delegateIds = delegateIds == null ? null : List.copyOf(delegateIds);
      recipientIds = recipientIds == null ? null : List.copyOf(recipientIds);
      if (participants != null) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : participants.entrySet()) {
          copy.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
        }
        participants = Collections.unmodifiableMap(copy);
      }
    }

    public static Resource empty() {
      return new Resource(
          null, null, null, null, null, null, null, null, null, "C1", null, null, null, null, null);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Context(@JsonProperty("now") Long now) {}
}
