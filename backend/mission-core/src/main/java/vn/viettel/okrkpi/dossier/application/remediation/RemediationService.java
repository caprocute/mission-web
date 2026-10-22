package vn.viettel.okrkpi.dossier.application.remediation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository.ItemRow;
import vn.viettel.okrkpi.dossier.application.remediation.port.RemediationItemRepository.Origin;
import vn.viettel.okrkpi.dossier.domain.meeting.Participant;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Theo dõi khắc phục hạn chế (CORE-06-04, BR-11-08, SCR-1101 vùng 3): hạn chế các kỳ trước của
 * người được kiểm điểm tự hiện ở hội nghị kỳ sau cùng hạn chế do hội nghị tạo; thư ký cập nhật
 * trạng thái {@code MO} → {@code DANG_KHAC_PHUC} → {@code DA_KHAC_PHUC}/{@code CHUA_KHAC_PHUC}.
 */
public class RemediationService {

  static final String TX = "dossierTransactionManager";
  static final String MODULE = "DOSSIER";
  static final int MAX_NOTE = 2000;

  static final String MO = "MO";
  static final String DANG_KHAC_PHUC = "DANG_KHAC_PHUC";
  static final String DA_KHAC_PHUC = "DA_KHAC_PHUC";
  static final String CHUA_KHAC_PHUC = "CHUA_KHAC_PHUC";

  /**
   * Bước chuyển hợp lệ. {@code CHUA_KHAC_PHUC} được mở lại {@code DANG_KHAC_PHUC} hoặc xác nhận
   * {@code DA_KHAC_PHUC} ở kỳ sau vì hạn chế tiếp tục được theo dõi (suy luận); {@code
   * DA_KHAC_PHUC} là trạng thái cuối.
   */
  private static final Map<String, Set<String>> NEXT =
      Map.of(
          MO, Set.of(DANG_KHAC_PHUC, DA_KHAC_PHUC, CHUA_KHAC_PHUC),
          DANG_KHAC_PHUC, Set.of(DA_KHAC_PHUC, CHUA_KHAC_PHUC),
          CHUA_KHAC_PHUC, Set.of(DANG_KHAC_PHUC, DA_KHAC_PHUC),
          DA_KHAC_PHUC, Set.of());

  private final ReviewMeetingRepository meetings;
  private final RemediationItemRepository items;
  private final CommentSealer sealer;
  private final Clock clock;

  /** Một hạn chế kèm nội dung đã giải mã. */
  public record ItemView(ItemRow row, String content) {}

  public RemediationService(
      ReviewMeetingRepository meetings,
      RemediationItemRepository items,
      CommentSealer sealer,
      Clock clock) {
    this.meetings = Objects.requireNonNull(meetings, "meetings");
    this.items = Objects.requireNonNull(items, "items");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Hạn chế hiện ở hội nghị: thành phần hội nghị hoặc người thuộc đơn vị tổ chức mới xem được (nội
   * dung C2). Người được kiểm điểm của hội nghị một người là người đó; hội nghị tập thể là mọi
   * thành phần.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<ItemView> list(
      TenantId tenant,
      UUID actor,
      UUID actorUnit,
      UUID meetingId,
      Origin origin,
      UUID after,
      int limit) {
    MeetingRow meeting = meetings.find(tenant, meetingId).orElseThrow(NoSuchElementException::new);
    if (!meeting.involves(actor) && !meeting.orgUnitId().equals(actorUnit)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xem hạn chế của hội nghị này.");
    }
    List<UUID> persons =
        meeting.subjectPersonId() != null
            ? List.of(meeting.subjectPersonId())
            : meeting.participants().stream().map(Participant::personId).distinct().toList();
    List<ItemView> out = new ArrayList<>();
    for (ItemRow r :
        items.listForMeeting(
            tenant, meetingId, meeting.periodId(), persons, origin, after, limit)) {
      out.add(new ItemView(r, open(tenant, r)));
    }
    return out;
  }

  /**
   * Thư ký cập nhật trạng thái khắc phục: thư ký của hội nghị nguồn hoặc của một hội nghị chưa ký
   * biên bản đang kiểm điểm người phải khắc phục. Xác nhận đã hoặc chưa khắc phục ghi người, thời
   * điểm xác nhận. Không kiểm khoá kỳ vì hạn chế được theo dõi sang kỳ sau, khi kỳ nguồn đã khoá
   * (suy luận). {@code note} chỉ được kiểm độ dài: TL-03 chưa có cột lưu ghi chú xác nhận.
   */
  @Transactional(TX)
  public ItemView update(
      TenantId tenant, UUID actor, UUID id, long expectedVersion, String status, String note) {
    ItemRow current = items.lock(tenant, id).orElseThrow(NoSuchElementException::new);
    boolean sourceSecretary =
        meetings
            .find(tenant, current.meetingId())
            .map(m -> m.secretaryPersonId().equals(actor))
            .orElse(false);
    if (!sourceSecretary && !meetings.isOpenSecretaryFor(tenant, actor, current.personId())) {
      throw new BusinessException(
          "DOSSIER-403-NOT-SECRETARY", "Chỉ thư ký hội nghị cập nhật trạng thái khắc phục.");
    }
    if (current.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion());
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    if (status == null || !NEXT.containsKey(status)) {
      errors.add(new ProblemFieldError("status", "Trạng thái khắc phục không hợp lệ.", null));
    }
    if (note != null && note.length() > MAX_NOTE) {
      errors.add(new ProblemFieldError("note", "Ghi chú tối đa 2.000 ký tự.", null));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "DOSSIER-422-VALIDATION", "Thông tin cập nhật khắc phục chưa hợp lệ.", errors);
    }
    if (!NEXT.get(current.status()).contains(status)) {
      throw new BusinessException(
          "DOSSIER-409-INVALID-STATE",
          DA_KHAC_PHUC.equals(current.status())
              ? "Hạn chế đã được xác nhận khắc phục."
              : "Không chuyển được sang trạng thái khắc phục này.");
    }
    boolean verified = DA_KHAC_PHUC.equals(status) || CHUA_KHAC_PHUC.equals(status);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    ItemRow next =
        new ItemRow(
            current.id(),
            current.periodId(),
            current.periodBucket(),
            current.personId(),
            current.meetingId(),
            current.reviewCommentId(),
            current.cipher(),
            current.dekId(),
            current.duePeriodId(),
            status,
            verified ? actor : null,
            verified ? now : null,
            current.rowVersion());
    long version = items.update(tenant, next, expectedVersion, actor);
    if (version == 0) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion() + 1);
    }
    ItemRow saved =
        new ItemRow(
            next.id(),
            next.periodId(),
            next.periodBucket(),
            next.personId(),
            next.meetingId(),
            next.reviewCommentId(),
            next.cipher(),
            next.dekId(),
            next.duePeriodId(),
            next.status(),
            next.verifiedBy(),
            next.verifiedAt(),
            version);
    return new ItemView(saved, open(tenant, saved));
  }

  private String open(TenantId tenant, ItemRow r) {
    return sealer.open(tenant, CommentSealer.REMEDIATION_COLUMN, r.id(), r.cipher());
  }
}
