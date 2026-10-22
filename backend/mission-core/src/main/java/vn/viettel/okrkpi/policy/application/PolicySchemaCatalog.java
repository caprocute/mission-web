package vn.viettel.okrkpi.policy.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.policy.application.PolicyResolution.VersionContent;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.PackVersionRow;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.policy.domain.SemVer;
import vn.viettel.okrkpi.policy.domain.SemVerRange;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hiện thực {@link PolicySchemaPort} của MOD-08 (CORE-05-03): giải {@code schemaRef} dạng {@code
 * policy://<mã gói>/forms/<biểu mẫu>@<phiên bản>} trong dải {@code requires.policyPack} của UI
 * Pack, liệt kê trường của khối {@code forms.<biểu mẫu>.schema.properties} trong manifest, trường
 * tính điểm lấy từ biểu mẫu và trạng thái ngừng của gói.
 */
public class PolicySchemaCatalog implements PolicySchemaPort {

  private static final String SCHEME = "policy://";
  private static final String FORMS = "/forms/";

  private final ObjectProvider<PolicyResolution> resolutions;
  private final ObjectProvider<DefaultPolicyQueryService> queries;

  /** Nhận bean qua {@link ObjectProvider} vì MOD-08 dựng cổng khi chính nó đang được dựng. */
  public PolicySchemaCatalog(
      ObjectProvider<PolicyResolution> resolutions,
      ObjectProvider<DefaultPolicyQueryService> queries) {
    this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
    this.queries = Objects.requireNonNull(queries, "queries");
  }

  @Override
  public Optional<Set<String>> fields(TenantId tenantId, String schemaRef, String policyPackRange) {
    if (schemaRef == null || !schemaRef.startsWith(SCHEME)) {
      return Optional.empty();
    }
    String path = schemaRef.substring(SCHEME.length());
    String exact = null;
    int at = path.lastIndexOf('@');
    if (at >= 0) {
      exact = path.substring(at + 1);
      path = path.substring(0, at);
    }
    int forms = path.indexOf(FORMS);
    if (forms <= 0) {
      return Optional.empty();
    }
    String code = path.substring(0, forms);
    String form = path.substring(forms + FORMS.length());
    Optional<SemVerRange> range = SemVerRange.parse(policyPackRange);
    if (range.isEmpty() || (range.get().code() != null && !range.get().code().equals(code))) {
      return Optional.empty();
    }
    String wanted = exact;
    return resolutions.getObject().versionsOfPack(tenantId, code).stream()
        .filter(v -> v.status() != PolicyVersionStatus.NHAP)
        .filter(v -> wanted == null || wanted.equals(v.version()))
        .filter(v -> SemVer.parse(v.version()).map(range.get()::includes).orElse(false))
        .max(Comparator.comparing(v -> SemVer.parse(v.version()).orElseThrow()))
        .flatMap(v -> queries.getObject().content(tenantId, v.versionId()))
        .map(c -> c.formFields().get(form));
  }

  @Override
  public boolean retired(TenantId tenantId, String policyPackRef) {
    Optional<SemVerRange> range = SemVerRange.parse(policyPackRef);
    if (range.isEmpty() || range.get().code() == null) {
      return false;
    }
    List<PackVersionRow> matching =
        resolutions.getObject().versionsOfPack(tenantId, range.get().code()).stream()
            .filter(v -> SemVer.parse(v.version()).map(range.get()::includes).orElse(false))
            .filter(v -> v.status() != PolicyVersionStatus.NHAP)
            .toList();
    return !matching.isEmpty()
        && matching.stream().allMatch(v -> v.status() == PolicyVersionStatus.NGUNG);
  }

  @Override
  public Map<String, String> scoredFields(TenantId tenantId, UUID policyPackVersionId) {
    if (policyPackVersionId == null) {
      return Map.of();
    }
    return queries
        .getObject()
        .content(tenantId, policyPackVersionId)
        .map(VersionContent::scoredFields)
        .orElse(Map.of());
  }
}
