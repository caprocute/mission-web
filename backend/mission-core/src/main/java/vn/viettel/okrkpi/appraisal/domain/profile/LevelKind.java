package vn.viettel.okrkpi.appraisal.domain.profile;

import java.util.Set;

/**
 * Bốn mức lưu riêng của BR-09-12 ({@code rating_proposal.level_kind}), kèm vai trò được đưa ra mức
 * (TL-03 {@code ck_rating_proposal__role}) và trạng thái hồ sơ mà ở đó mức được ghi.
 */
public enum LevelKind {
  TU_DE_XUAT(Set.of("staff"), AppraisalStatus.TU_DANH_GIA),
  THAM_DINH(Set.of("appraiser"), AppraisalStatus.CHO_THAM_DINH),
  DE_XUAT(Set.of("manager", "appraiser"), AppraisalStatus.DA_THAM_DINH),
  QUYET_DINH(Set.of("decider"), AppraisalStatus.CHO_QUYET_DINH);

  private final Set<String> roles;
  private final AppraisalStatus recordableIn;

  LevelKind(Set<String> roles, AppraisalStatus recordableIn) {
    this.roles = roles;
    this.recordableIn = recordableIn;
  }

  /** Vai trò ({@code actor_role}) được đưa ra mức ở bước này. */
  public Set<String> roles() {
    return roles;
  }

  /** Trạng thái hồ sơ mà mức của bước này được ghi. */
  public AppraisalStatus recordableIn() {
    return recordableIn;
  }
}
