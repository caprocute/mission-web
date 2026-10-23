package vn.viettel.okrkpi.oversight.application.complaint;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi.Participants;
import vn.viettel.okrkpi.oversight.application.port.ComplaintDirectory;
import vn.viettel.okrkpi.oversight.application.port.ComplaintHandlingRepository.Handled;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService.Actor;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintHandlerRules;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm thẩm quyền xử lý khiếu nại ở máy chủ (BR-12-04, SCR-1202 "Quyền"), không phụ thuộc lớp OPA:
 * người đã tham gia hồ sơ bị 403 tách vai trò (MSG-12-05); người tiếp nhận phải là ứng viên của
 * {@link ComplaintHandlerRules}; các thao tác sau tiếp nhận chỉ của người đã tiếp nhận.
 */
final class ComplaintAccess {

  static final String ROLE_APPRAISER = "appraiser";
  static final String ROLE_DECIDER = "decider";
  static final String ROLE_MANAGER = "manager";
  static final String SOD = "OVERSIGHT-403-SEPARATION-OF-DUTIES";
  static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";
  static final String MSG_SOD = "MSG-12-05";

  private final ComplaintDirectory directory;
  private final Clock clock;

  ComplaintAccess(ComplaintDirectory directory, Clock clock) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Người tham gia hồ sơ; không đọc được thì chỉ có người được đánh giá. */
  Participants participants(TenantId tenant, Complaint c) {
    return directory
        .participants(tenant, c.profileId())
        .orElseGet(
            () ->
                new Participants(
                    c.profileId(),
                    c.complainantId(),
                    c.handlerUnitId(),
                    c.periodId(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of()));
  }

  /** Người đã nghiệm thu, thẩm định, đề xuất, quyết định hồ sơ hoặc là người khiếu nại. */
  boolean conflicted(TenantId tenant, Complaint c, UUID person) {
    return person.equals(c.complainantId()) || participants(tenant, c).took(person);
  }

  void requireNoConflict(TenantId tenant, Complaint c, UUID person) {
    if (conflicted(tenant, c, person)) {
      throw new BusinessException(
          SOD,
          "Đồng chí không thể xử lý khiếu nại này vì đã tham gia đánh giá hồ sơ liên quan.",
          List.of(),
          MSG_SOD);
    }
  }

  /** Ứng viên xử lý: {@code appraiser} ở đơn vị xử lý, không còn ai thì nâng cấp trên. */
  ComplaintHandlerRules.Candidates candidates(TenantId tenant, Complaint c) {
    if (c.handlerUnitId() == null) {
      return ComplaintHandlerRules.candidates(List.of(), p -> true);
    }
    List<UUID> chain = new ArrayList<>();
    chain.add(c.handlerUnitId());
    chain.addAll(directory.ancestorsOf(tenant, c.handlerUnitId()));
    List<Set<UUID>> levels = new ArrayList<>();
    for (UUID unit : chain) {
      levels.add(
          new LinkedHashSet<>(directory.holders(tenant, ROLE_APPRAISER, unit, clock.instant())));
    }
    Participants p = participants(tenant, c);
    return ComplaintHandlerRules.candidates(
        levels, person -> person.equals(c.complainantId()) || p.took(person));
  }

  /** Người tiếp nhận: không xung đột và là ứng viên hợp lệ. */
  void requireCandidate(TenantId tenant, Actor actor, Complaint c) {
    if (!actor.roles().contains(ROLE_APPRAISER)) {
      throw outOfScope();
    }
    requireNoConflict(tenant, c, actor.userId());
    if (!candidates(tenant, c).people().contains(actor.userId())) {
      throw outOfScope();
    }
  }

  /** Người đã tiếp nhận; kiểm lại tách vai trò vì người tham gia hồ sơ có thể đổi. */
  void requireHandler(TenantId tenant, Actor actor, Handled h) {
    UUID handler = h.handling().handlerPersonId();
    if (handler == null || !handler.equals(actor.userId())) {
      throw outOfScope();
    }
    requireNoConflict(tenant, h.complaint(), actor.userId());
  }

  /** Đơn vị xử lý nằm trong cây của đơn vị phân công đang chọn. */
  boolean inScope(TenantId tenant, Actor actor, Complaint c) {
    return actor.unitId() != null
        && c.handlerUnitId() != null
        && directory.scopeOf(tenant, actor.unitId()).contains(c.handlerUnitId());
  }

  Set<UUID> scopeOf(TenantId tenant, UUID unit) {
    return directory.scopeOf(tenant, unit);
  }

  List<UUID> holders(TenantId tenant, String role, UUID unit) {
    return directory.holders(tenant, role, unit, clock.instant());
  }

  static boolean staff(Actor actor) {
    return actor.roles().contains(ROLE_APPRAISER) || actor.roles().contains(ROLE_DECIDER);
  }

  static BusinessException outOfScope() {
    return new BusinessException(OUT_OF_SCOPE, "Đồng chí không có quyền xử lý khiếu nại này.");
  }
}
