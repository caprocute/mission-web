package vn.viettel.okrkpi.policy.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đọc phục vụ phân giải gói của CORE-05-03 trên {@code policy.policy_binding}, {@code
 * policy.policy_pack_version} và {@code policy.policy_pack}; ghim phiên bản cho kỳ (BR-08-07).
 */
public interface PolicyResolutionRepository {

  /** Phiên bản kèm thông tin gói và khoảng hiệu lực trong manifest. */
  record PackVersionRow(
      UUID packId,
      String code,
      String packFlow,
      UUID versionId,
      String version,
      PolicyVersionStatus status,
      LocalDate effectiveFrom,
      String tuKy,
      String denKy,
      String contentHash,
      String bundleObjectKey) {}

  /** Một dòng {@code policy_binding} cùng phiên bản đã gắn. */
  record BindingRow(
      UUID bindingId,
      String flow,
      UUID subjectGroupId,
      UUID orgUnitId,
      boolean frozen,
      PackVersionRow version) {}

  /** Các dòng gắn của kỳ với luồng {@code flow} hoặc {@code CA_HAI}. */
  List<BindingRow> bindings(TenantId tenant, UUID periodId, String flow);

  /** Phiên bản {@code DA_PHAT_HANH} hoặc {@code NGUNG} của các gói thuộc luồng. */
  List<PackVersionRow> releasedVersions(TenantId tenant, String flow);

  /** Mọi phiên bản của gói mã {@code code}. */
  List<PackVersionRow> versionsOfPack(TenantId tenant, String code);

  Optional<PackVersionRow> version(TenantId tenant, UUID versionId);

  /** Manifest JSON của phiên bản. */
  Optional<String> manifest(TenantId tenant, UUID versionId);

  /**
   * Ghim phiên bản cho cả tenant ở kỳ và luồng (khối, đơn vị trống); đã có dòng cùng phạm vi thì
   * giữ dòng cũ và trả {@code false}.
   */
  boolean pin(
      TenantId tenant, UUID bindingId, UUID periodId, String flow, UUID versionId, UUID actor);
}
