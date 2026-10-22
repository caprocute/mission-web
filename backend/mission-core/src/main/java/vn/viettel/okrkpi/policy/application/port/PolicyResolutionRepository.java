package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Truy vấn phân giải Policy Pack theo kỳ trên {@code policy.policy_binding} (CORE-05-03). */
public interface PolicyResolutionRepository {

  /**
   * Gắn khớp cụ thể nhất: đơn vị trước, rồi khối, rồi toàn tenant; gắn đúng luồng trước gắn {@code
   * CA_HAI}.
   */
  Optional<ResolvedPack> matchBinding(
      TenantId tenant, UUID periodId, String flow, UUID subjectGroupId, UUID orgUnitId);

  /** Phiên bản {@code DA_PHAT_HANH} hoặc {@code NGUNG} của các gói cùng luồng, kèm manifest. */
  List<PublishedVersion> publishedVersions(TenantId tenant, String flow);

  /** Ghim gắn toàn tenant cho kỳ; đã có gắn cùng phạm vi thì giữ gắn cũ. */
  void pinBinding(
      TenantId tenant,
      UUID bindingId,
      UUID versionId,
      UUID periodId,
      String flow,
      boolean frozen,
      UUID actor);

  /** Phiên bản theo mã định danh, kèm manifest và trạng thái. */
  Optional<PublishedVersion> version(TenantId tenant, UUID versionId);

  /** Phiên bản theo mã gói và số phiên bản. */
  Optional<PublishedVersion> versionByCode(TenantId tenant, String code, String version);

  /** Một dòng phiên bản kèm gói và manifest JSON. */
  record PublishedVersion(
      UUID packId,
      String code,
      String origin,
      String flow,
      UUID versionId,
      String version,
      PolicyVersionStatus status,
      String contentHash,
      String bundleObjectKey,
      java.time.LocalDate effectiveFrom,
      String manifestJson) {}
}
