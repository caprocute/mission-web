package vn.viettel.okrkpi.appraisal.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ đánh giá cho gói hồ sơ số của MOD-06 (CORE-06-06, QT-11 bước 10–12): hồ sơ của một đơn vị
 * trong kỳ, kết quả đã quyết định làm dữ liệu sinh biểu mẫu (BR-11-02) và danh mục biểu mẫu, phiên
 * bản mẫu theo Policy Pack của hồ sơ (BR-11-03). MOD-06 không đọc CSDL của MOD-04 (TL-01 mục 2.1).
 */
public interface DossierProfilesApi {

  /** Tham số Policy Pack: danh sách mã biểu mẫu sinh cho mỗi hồ sơ cá nhân (suy luận). */
  String PERSONAL_FORMS_PARAM = "ho_so_so.bieu_mau_ca_nhan";

  /** Tham số Policy Pack: danh sách mã biểu mẫu sinh một lần cho cả đơn vị (suy luận). */
  String UNIT_FORMS_PARAM = "ho_so_so.bieu_mau_don_vi";

  /** Tham số Policy Pack: bảng mã biểu mẫu → phiên bản mẫu ghim cho kỳ (suy luận). */
  String TEMPLATE_VERSIONS_PARAM = "ho_so_so.phien_ban_mau";

  /** Gói không khai danh mục cá nhân: bảng chấm điểm của từng người. */
  List<String> DEFAULT_PERSONAL_FORMS = List.of("BANG_CHAM_DIEM");

  /** Gói không khai danh mục đơn vị: Mẫu 3 tổng hợp kết quả của đơn vị. */
  List<String> DEFAULT_UNIT_FORMS = List.of("MAU_3");

  /** Trạng thái hồ sơ đã có quyết định xếp loại. */
  Set<String> DECIDED = Set.of("DA_QUYET_DINH", "DA_KY_SO", "DA_KHOA");

  /**
   * Policy Pack của hồ sơ: mã, phiên bản (đóng tem), danh mục biểu mẫu và phiên bản mẫu ghim.
   *
   * @param templateVersions mã biểu mẫu → phiên bản mẫu; vắng là phiên bản đang hiệu lực mới nhất
   */
  record Pack(
      String code,
      String version,
      List<String> personalForms,
      List<String> unitForms,
      Map<String, Integer> templateVersions) {

    public Pack {
      personalForms = personalForms == null ? List.of() : List.copyOf(personalForms);
      unitForms = unitForms == null ? List.of() : List.copyOf(unitForms);
      templateVersions = templateVersions == null ? Map.of() : Map.copyOf(templateVersions);
    }
  }

  /**
   * Một hồ sơ: đơn vị là đơn vị chốt của hồ sơ, mức và điểm là mức quyết định và tổng điểm của ảnh
   * chụp hiện hành.
   */
  record UnitProfile(
      UUID id,
      UUID personId,
      UUID periodId,
      int periodBucket,
      UUID orgUnitId,
      String flow,
      String status,
      String ratingCode,
      BigDecimal score,
      UUID scoreSnapshotId,
      boolean head,
      Pack pack) {

    public UnitProfile {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(personId, "personId");
      Objects.requireNonNull(periodId, "periodId");
      Objects.requireNonNull(orgUnitId, "orgUnitId");
      Objects.requireNonNull(status, "status");
    }

    public boolean decided() {
      return DECIDED.contains(status);
    }
  }

  /** Một hồ sơ ở mọi trạng thái. */
  Optional<UnitProfile> find(TenantId tenant, UUID profileId);

  /** Mọi hồ sơ của đơn vị trong kỳ, theo mã hồ sơ. */
  List<UnitProfile> unitProfiles(TenantId tenant, UUID periodId, UUID orgUnitId);

  /** Người có hồ sơ của đơn vị trong kỳ chưa có quyết định xếp loại (đọc nhẹ, không kèm gói). */
  List<UUID> undecidedPersons(TenantId tenant, UUID periodId, UUID orgUnitId);

  /** Có ít nhất một hồ sơ đã quyết định của đơn vị trong kỳ. */
  boolean anyDecided(TenantId tenant, UUID periodId, UUID orgUnitId);
}
