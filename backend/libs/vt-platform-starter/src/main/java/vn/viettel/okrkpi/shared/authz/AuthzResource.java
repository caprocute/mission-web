package vn.viettel.okrkpi.shared.authz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thông tin tài nguyên truyền vào {@link RequiresPermission} để dựng {@link OpaInput.Resource}
 * (NEN-014, ADR-21).
 */
public record AuthzResource(
    String type,
    String id,
    String tenantId,
    String ownerUnitId,
    List<String> ownerIds,
    List<String> assigneeIds,
    List<String> designatedIds,
    List<String> delegateIds,
    String flow,
    String classification,
    List<String> recipientIds,
    Map<String, List<String>> participants,
    Boolean smallUnitException,
    String grantedRole,
    Boolean concluded)
    implements AuthzResourceProvider {

  public AuthzResource {
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

  @Override
  public AuthzResource toAuthzResource() {
    return this;
  }

  public OpaInput.Resource toOpaResource(String fallbackTenantId, String fallbackClassification) {
    return new OpaInput.Resource(
        type,
        id,
        tenantId != null ? tenantId : fallbackTenantId,
        ownerUnitId,
        ownerIds,
        assigneeIds,
        designatedIds,
        delegateIds,
        flow,
        classification != null ? classification : fallbackClassification,
        recipientIds,
        participants,
        smallUnitException,
        grantedRole,
        concluded);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String type;
    private String id;
    private String tenantId;
    private String ownerUnitId;
    private List<String> ownerIds;
    private List<String> assigneeIds;
    private List<String> designatedIds;
    private List<String> delegateIds;
    private String flow;
    private String classification;
    private List<String> recipientIds;
    private Map<String, List<String>> participants;
    private Boolean smallUnitException;
    private String grantedRole;
    private Boolean concluded;

    public Builder type(String type) {
      this.type = type;
      return this;
    }

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder tenantId(String tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    public Builder ownerUnitId(String ownerUnitId) {
      this.ownerUnitId = ownerUnitId;
      return this;
    }

    public Builder ownerIds(List<String> ownerIds) {
      this.ownerIds = ownerIds == null ? null : List.copyOf(ownerIds);
      return this;
    }

    public Builder assigneeIds(List<String> assigneeIds) {
      this.assigneeIds = assigneeIds == null ? null : List.copyOf(assigneeIds);
      return this;
    }

    public Builder designatedIds(List<String> designatedIds) {
      this.designatedIds = designatedIds == null ? null : List.copyOf(designatedIds);
      return this;
    }

    public Builder delegateIds(List<String> delegateIds) {
      this.delegateIds = delegateIds == null ? null : List.copyOf(delegateIds);
      return this;
    }

    public Builder flow(String flow) {
      this.flow = flow;
      return this;
    }

    public Builder classification(String classification) {
      this.classification = classification;
      return this;
    }

    public Builder recipientIds(List<String> recipientIds) {
      this.recipientIds = recipientIds == null ? null : List.copyOf(recipientIds);
      return this;
    }

    public Builder participants(Map<String, List<String>> participants) {
      if (participants == null) {
        this.participants = null;
      } else {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : participants.entrySet()) {
          copy.put(e.getKey(), e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
        }
        this.participants = copy;
      }
      return this;
    }

    public Builder smallUnitException(Boolean smallUnitException) {
      this.smallUnitException = smallUnitException;
      return this;
    }

    public Builder grantedRole(String grantedRole) {
      this.grantedRole = grantedRole;
      return this;
    }

    public Builder concluded(Boolean concluded) {
      this.concluded = concluded;
      return this;
    }

    public AuthzResource build() {
      return new AuthzResource(
          type,
          id,
          tenantId,
          ownerUnitId,
          ownerIds,
          assigneeIds,
          designatedIds,
          delegateIds,
          flow,
          classification,
          recipientIds,
          participants,
          smallUnitException,
          grantedRole,
          concluded);
    }
  }
}
