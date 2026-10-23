package vn.viettel.okrkpi.okr.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.okr.domain.ObjectiveLifecycle;
import vn.viettel.okrkpi.okr.domain.ObjectiveLifecycle.Event;

/**
 * Thao tác người gọi được làm trên một mục tiêu và KR ({@code permittedActions}, YC-PT-21), cùng
 * điều kiện theo đối tượng mà dịch vụ nghiệp vụ kiểm khi thực hiện. Vai trò và phạm vi đơn vị do ma
 * trận chức năng ở giao diện và OPA kiểm; đây chỉ là gợi ý hiển thị.
 */
public final class OkrPermittedActions {

  public static final String SOAN_DON_VI = "SCR-0202.soan-don-vi";
  public static final String SOAN_CA_NHAN = "SCR-0202.soan-ca-nhan";
  public static final String TRINH = "SCR-0202.trinh";
  public static final String DUYET = "SCR-0203.duyet";
  public static final String TRA_LAI = "SCR-0203.tra-lai";
  public static final String CHECK_IN = "SCR-0201.check-in";

  private OkrPermittedActions() {}

  /**
   * Soạn và trình khi mục tiêu {@code NHAP} (mục tiêu cá nhân chỉ của chính mình, như {@code
   * OkrComposeService}); duyệt khi {@code CHO_DUYET} và người gọi không phải người soạn (BR-02-08),
   * trả lại khi {@code CHO_DUYET} ({@code OkrReviewService.sendBack} không xét người soạn).
   */
  public static Set<String> objective(
      String status, String ownerType, UUID ownerPersonId, UUID createdBy, UUID actor) {
    Set<String> out = new LinkedHashSet<>();
    if (actor == null) {
      return out;
    }
    if (ObjectiveLifecycle.next(status, Event.TRINH).isPresent()) {
      if (!"CA_NHAN".equals(ownerType)) {
        out.add(SOAN_DON_VI);
        out.add(TRINH);
      } else if (actor.equals(ownerPersonId)) {
        out.add(SOAN_CA_NHAN);
        out.add(TRINH);
      }
    }
    if (ObjectiveLifecycle.next(status, Event.DUYET).isPresent() && !actor.equals(createdBy)) {
      out.add(DUYET);
    }
    if (ObjectiveLifecycle.next(status, Event.TRA_LAI).isPresent()) {
      out.add(TRA_LAI);
    }
    return out;
  }

  /**
   * Check-in KR (BR-03-01, {@code OkrCheckinService}): mục tiêu {@code DA_DUYET}, người gọi là chủ
   * KR (chủ mục tiêu cá nhân, mục tiêu tập thể thì người soạn) hoặc người được giao check-in thay.
   */
  public static Set<String> keyResult(
      String objectiveStatus, UUID ownerPersonId, UUID createdBy, UUID actor, boolean delegate) {
    if (actor == null || !"DA_DUYET".equals(objectiveStatus)) {
      return Set.of();
    }
    UUID owner = ownerPersonId != null ? ownerPersonId : createdBy;
    return actor.equals(owner) || delegate ? Set.of(CHECK_IN) : Set.of();
  }

  /** Chủ KR theo cùng quy ước của {@link #keyResult}. */
  public static boolean ownsKeyResult(UUID ownerPersonId, UUID createdBy, UUID actor) {
    return actor != null && actor.equals(ownerPersonId != null ? ownerPersonId : createdBy);
  }
}
