package vn.viettel.okrkpi.policy.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner.SignedHash;
import vn.viettel.okrkpi.policy.domain.CustomerProfile;
import vn.viettel.okrkpi.policy.domain.CustomerProfileVersion;
import vn.viettel.okrkpi.policy.domain.ParamConstraint;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ tập khách hàng, phiên bản, khung tham số và cột ghim trong {@code policy_binding}
 * (CORE-05-12, BS-03).
 */
public interface CustomerProfileRepository {

  List<CustomerProfile> listProfiles(TenantId tenant, String query, String afterCode, int limit);

  Optional<CustomerProfile> findProfile(TenantId tenant, UUID profileId);

  /** Chèn hồ sơ; mã đã có trả {@code false}. */
  boolean insertProfile(TenantId tenant, CustomerProfile profile);

  /** Tối đa {@code perProfile} phiên bản mới nhất của mỗi hồ sơ. */
  Map<UUID, List<CustomerProfileVersion>> recentVersions(
      TenantId tenant, Collection<UUID> profileIds, int perProfile);

  /** Phiên bản mới nhất trước, sau {@code afterId} (con trỏ). */
  List<CustomerProfileVersion> listVersions(
      TenantId tenant, UUID profileId, PolicyVersionStatus status, UUID afterId, int limit);

  Optional<CustomerProfileVersion> findVersion(TenantId tenant, UUID profileId, String version);

  Optional<CustomerProfileVersion> findVersionById(TenantId tenant, UUID versionId);

  List<String> versionNumbers(TenantId tenant, UUID profileId);

  /** Chèn phiên bản; số phiên bản đã có trả {@code false}. */
  boolean insertVersion(TenantId tenant, CustomerProfileVersion version);

  /**
   * Ghi trạng thái, bản kê, băm, ghi chú nếu {@code row_version} còn bằng {@code expected}. Khi đi
   * từ {@code DAT_KIEM_THU} về {@code NHAP}, đổi trạng thái trước rồi mới đổi nội dung trong cùng
   * giao dịch để trigger đóng băng cho qua.
   */
  boolean storeVersion(TenantId tenant, CustomerProfileVersion version, long expected);

  /** Ghi chữ ký và chuyển {@code DA_PHAT_HANH} nếu còn {@code CHO_PHE_DUYET}. */
  boolean storeApproval(
      TenantId tenant, CustomerProfileVersion version, SignedHash signed, long expected);

  List<ParamConstraint> constraints(TenantId tenant, UUID versionId);

  int countConstraints(TenantId tenant, UUID versionId);

  /** Thay toàn bộ khung tham số của phiên bản. */
  void replaceConstraints(
      TenantId tenant, UUID versionId, List<ParamConstraint> constraints, UUID actor);

  /** Phiên bản {@code DA_PHAT_HANH}, mới phát hành trước. */
  List<CustomerProfileVersion> publishedVersions(TenantId tenant);

  /** Ghim phiên bản hồ sơ vào gắn {@code bindingId}; gắn không có trả {@code false}. */
  boolean pinBinding(TenantId tenant, UUID bindingId, UUID profileVersionId, UUID actor);

  /** Phiên bản hồ sơ ghim ở gắn {@code bindingId}. */
  Optional<UUID> pinnedVersion(TenantId tenant, UUID bindingId);
}
