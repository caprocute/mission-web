package vn.viettel.okrkpi.policy.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyBinding;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho Policy Pack của MOD-05 trên schema {@code policy}. */
public interface PolicyPackRepository {

  /** Gói sắp theo mã, sau {@code afterCode} (con trỏ). */
  List<PolicyPack> listPacks(
      TenantId tenant, String flow, PackOrigin origin, String query, String afterCode, int limit);

  Optional<PolicyPack> findPack(TenantId tenant, UUID packId);

  Optional<PolicyPack> findPackByCode(TenantId tenant, String code);

  /** Chèn gói; mã đã có trả {@code false}. */
  boolean insertPack(TenantId tenant, PolicyPack pack);

  /** Tối đa {@code perPack} phiên bản mới nhất của mỗi gói. */
  Map<UUID, List<PolicyPackVersion>> recentVersions(
      TenantId tenant, Collection<UUID> packIds, int perPack);

  /** Phiên bản mới nhất trước, sau {@code afterId} (con trỏ, UUIDv7 tăng theo thời gian). */
  List<PolicyPackVersion> listVersions(
      TenantId tenant, UUID packId, PolicyVersionStatus status, UUID afterId, int limit);

  List<String> versionNumbers(TenantId tenant, UUID packId);

  Optional<PolicyPackVersion> findVersion(TenantId tenant, UUID packId, String version);

  Optional<PolicyPackVersion> findVersionById(TenantId tenant, UUID versionId);

  /** Chèn phiên bản; số phiên bản đã có trong gói trả {@code false}. */
  boolean insertVersion(TenantId tenant, PolicyPackVersion version);

  /**
   * Ghi bản sao tham số luồng duyệt vào {@code manifest.workflow_bindings.<processKind>} của phiên
   * bản còn {@code NHAP}, để {@code content_hash} phủ cả tham số luồng (CORE-05-11); phiên bản đã
   * rời {@code NHAP} trả {@code false}. Không tăng {@code row_version}, như khi lưu quy tắc.
   */
  boolean storeWorkflowBinding(
      TenantId tenant, UUID versionId, String processKind, String bindingJson, UUID actor);

  /** Ghi trạng thái mới nếu {@code row_version} còn bằng {@code expected}. */
  boolean storeStatus(TenantId tenant, PolicyPackVersion version, long expected);

  /**
   * Ghi trạng thái kèm {@code golden_pass_rate}, {@code last_test_run_id} sau lần chạy kiểm thử nếu
   * {@code row_version} còn bằng {@code expected} (CORE-05-07); đạt thì ghi cả {@code content_hash}
   * (CORE-05-09).
   */
  boolean storeTestOutcome(TenantId tenant, PolicyPackVersion version, long expected);

  List<PolicyRule> rules(TenantId tenant, UUID versionId);

  Optional<PolicyRule> findRule(TenantId tenant, UUID versionId, String ruleKey);

  void insertRules(TenantId tenant, List<PolicyRule> rules, UUID actor);

  List<DecisionTable> decisionTables(TenantId tenant, UUID versionId);

  void insertDecisionTables(TenantId tenant, List<DecisionTable> tables, UUID actor);

  List<PolicyBinding> bindings(TenantId tenant, UUID versionId);
}
