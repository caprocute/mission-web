package vn.viettel.okrkpi.scoring.infrastructure.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriComponentsBuilder;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.domain.DecisionTableSource;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.scoring.domain.PolicyRuleSource;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Đọc Policy Pack của MOD-05 qua hợp đồng {@code policies.yaml} (DV-07-02) bằng {@link
 * ResilientClient} (Resilience4j: time limiter 3 s, circuit breaker, bulkhead; instance {@code
 * policy-pack}). Phân giải bằng {@code resolvePolicyPack}; nội dung phiên bản bằng {@code
 * getPolicyPackVersion}, {@code listPolicyRules}, {@code listDecisionTables} và {@code
 * exportDecisionTableDmn}. Các thao tác nội dung hiện chưa khai {@code service_client} trong {@code
 * x-mission.roles} và gói đã ký ({@code bundleObjectKey}) chưa được sinh; ghi ở wiki trang 14.
 */
public class HttpPolicyPackSource implements PolicyPackSource {

  static final String BASE = "/api/v1/policies";
  static final String VERSION = BASE + "/packs/{id}/versions/{v}";

  private final ResilientClient client;

  public HttpPolicyPackSource(ResilientClient client) {
    this.client = client;
  }

  @Override
  public Optional<PinnedPolicy> resolve(PolicyBindingKey key) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath(BASE + "/resolve")
            .queryParam("tenant", key.tenantId())
            .queryParam("period", key.periodId())
            .queryParam("flow", key.flow());
    if (key.subjectGroupId() != null) {
      uri.queryParam("subjectGroup", key.subjectGroupId());
    }
    if (key.unitId() != null) {
      uri.queryParam("unitId", key.unitId());
    }
    Resolved r;
    try {
      r = client.get(uri.build().toUriString(), Resolved.class);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    if (r == null) {
      return Optional.empty();
    }
    return Optional.of(
        new PinnedPolicy(
            r.policyPackId(),
            r.code(),
            r.versionId(),
            r.version(),
            r.contentHash(),
            Boolean.TRUE.equals(r.frozen())));
  }

  @Override
  public PolicyContent load(UUID tenantId, UUID packId, UUID versionId, String version) {
    String v = version != null ? version : versionOf(packId, versionId);
    Version detail = client.get(VERSION, Version.class, packId, v);
    if (detail == null || !versionId.equals(detail.id())) {
      throw new IllegalStateException("MOD-05 trả phiên bản khác phiên bản đã yêu cầu");
    }
    Items<Rule> rules = client.get(VERSION + "/rules", RuleList.class, packId, v);
    Items<Table> tables = client.get(VERSION + "/decision-tables", TableList.class, packId, v);
    List<DecisionTableSource> dmn = new ArrayList<>();
    for (Table t : tables.items()) {
      String xml =
          client.get(VERSION + "/decision-tables/{key}/dmn", String.class, packId, v, t.tableKey());
      dmn.add(new DecisionTableSource(t.tableKey(), xml));
    }
    return new PolicyContent(
        new PinnedPolicy(packId, detail.packCode(), versionId, v, detail.contentHash(), false),
        detail.manifest(),
        rules.items().stream().map(HttpPolicyPackSource::rule).toList(),
        dmn);
  }

  private String versionOf(UUID packId, UUID versionId) {
    String cursor = null;
    do {
      UriComponentsBuilder uri =
          UriComponentsBuilder.fromPath(BASE + "/packs/" + packId + "/versions")
              .queryParam("limit", 200);
      if (cursor != null) {
        uri.queryParam("cursor", cursor);
      }
      VersionList page = client.get(uri.build().toUriString(), VersionList.class);
      for (Summary s : page.items()) {
        if (versionId.equals(s.id())) {
          return s.version();
        }
      }
      cursor = page.nextCursor();
    } while (cursor != null);
    throw new IllegalStateException("Không tìm thấy phiên bản " + versionId + " của gói " + packId);
  }

  private static PolicyRuleSource rule(Rule r) {
    Integer digits = null;
    String mode = null;
    if (r.rounding() != null) {
      digits = r.rounding().get("digits") instanceof Number n ? n.intValue() : null;
      mode = r.rounding().get("mode") instanceof String s ? s : null;
    }
    return new PolicyRuleSource(
        r.ruleKey(),
        r.expression(),
        r.resultType(),
        r.evalOrder() == null ? 0 : r.evalOrder(),
        digits,
        mode,
        r.legalRef());
  }

  /** {@code ResolvedPolicyPack}. */
  record Resolved(
      UUID policyPackId,
      String code,
      UUID versionId,
      String version,
      String contentHash,
      String bundleObjectKey,
      UUID bindingId,
      Boolean frozen) {}

  /** Trường cần dùng của {@code PolicyPackVersion}. */
  record Version(
      UUID id, String version, String packCode, Map<String, Object> manifest, String contentHash) {}

  record Summary(UUID id, String version) {}

  record VersionList(List<Summary> items, String nextCursor) {}

  record Rule(
      String ruleKey,
      String expression,
      String resultType,
      Map<String, Object> rounding,
      String legalRef,
      Integer evalOrder) {}

  record Table(String tableKey) {}

  interface Items<T> {
    List<T> items();
  }

  record RuleList(List<Rule> items) implements Items<Rule> {}

  record TableList(List<Table> items) implements Items<Table> {}
}
