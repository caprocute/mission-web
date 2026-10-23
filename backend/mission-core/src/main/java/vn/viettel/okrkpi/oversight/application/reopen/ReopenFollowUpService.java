package vn.viettel.okrkpi.oversight.application.reopen;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.oversight.application.port.ComplaintRepository;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.application.port.PeriodRelock;
import vn.viettel.okrkpi.oversight.application.port.ReopenForms;
import vn.viettel.okrkpi.oversight.application.port.ReopenForms.FormRef;
import vn.viettel.okrkpi.oversight.application.port.ReopenNotices;
import vn.viettel.okrkpi.oversight.application.port.ReopenProgressRepository;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository;
import vn.viettel.okrkpi.oversight.application.port.ReopenScopeProfiles;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenFollowUp;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenProgress;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điều phối phần còn lại của QT-13 sau EVT-22 (CORE-07-09, bước 10 đến 12).
 *
 * <ul>
 *   <li>Khi yêu cầu sang {@code DA_THUC_HIEN}: lập danh sách hồ sơ trong phạm vi cần theo dõi.
 *   <li>EVT-10 {@code TINH_LAI_HAU_KIEM}, EVT-17 và EVT-18 của các biểu mẫu bắt buộc (mặc định Mẫu
 *       2, Mẫu 3): ghi tiến độ từng hồ sơ; đủ mọi hồ sơ thì đề nghị MOD-08 khoá lại sớm.
 *   <li>Hết hạn mở mà chưa xong: đánh dấu "chưa hoàn tất hậu kiểm", báo người lập (EVT-21).
 *   <li>EVT-09 khoá lại: đặt {@code relocked_at}, chuyển {@code DA_DONG}, báo người lập và người xử
 *       lý khiếu nại đang thi hành làm căn cứ.
 * </ul>
 *
 * Mỗi lệnh khoá dòng yêu cầu ({@code FOR UPDATE}) nên các sự kiện của cùng yêu cầu được tuần tự
 * hoá; không xoá hay ghi đè ảnh chụp, biểu mẫu cũ (BR-13-08), mọi bước ghi sổ cái (BR-13-07).
 */
public class ReopenFollowUpService {

  static final String TX = ReopenRequestService.TX;

  /** Loại tác vụ tính lại của SVC-07 trong hậu kiểm (EVT-10 {@code jobKind}). */
  public static final String RECOMPUTE_KIND = "TINH_LAI_HAU_KIEM";

  /** Nguồn ký của biểu mẫu hồ sơ số ở SVC-10 (EVT-18 {@code sourceModule}, suy luận). */
  public static final String FORM_SOURCE = "dossier";

  /** Tác nhân hệ thống ghi vào {@code updated_by} và sổ cái. */
  public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  static final String OBJECT_TYPE = "oversight.reopen_request";

  private static final Logger log = LoggerFactory.getLogger(ReopenFollowUpService.class);

  private final ReopenRequestRepository requests;
  private final ReopenProgressRepository progress;
  private final ReopenScopeProfiles scope;
  private final ReopenForms forms;
  private final PeriodRelock relock;
  private final ComplaintRepository complaints;
  private final ReopenNotices notices;
  private final OversightAuditLog audit;
  private final Clock clock;
  private final Set<String> requiredForms;

  /**
   * @param relock {@code null} khi không đề nghị khoá lại sớm (kỳ chỉ khoá lại khi hết hạn)
   * @param complaints {@code null} khi không báo người xử lý khiếu nại
   * @param requiredForms mã biểu mẫu phải sinh lại và ký lại cho mỗi hồ sơ
   */
  public ReopenFollowUpService(
      ReopenRequestRepository requests,
      ReopenProgressRepository progress,
      ReopenScopeProfiles scope,
      ReopenForms forms,
      PeriodRelock relock,
      ComplaintRepository complaints,
      ReopenNotices notices,
      OversightAuditLog audit,
      Clock clock,
      Set<String> requiredForms) {
    this.requests = Objects.requireNonNull(requests, "requests");
    this.progress = Objects.requireNonNull(progress, "progress");
    this.scope = Objects.requireNonNull(scope, "scope");
    this.forms = Objects.requireNonNull(forms, "forms");
    this.relock = relock;
    this.complaints = complaints;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.requiredForms = Set.copyOf(requiredForms);
  }

  /**
   * Lập danh sách hồ sơ theo dõi cho các yêu cầu vừa sang {@code DA_THUC_HIEN}. Chạy lại an toàn.
   *
   * @return số yêu cầu vừa lập
   */
  @Transactional(TX)
  public int seedExecuted(TenantId tenant) {
    int seeded = 0;
    for (UUID id : progress.executedNotSeeded(tenant, 100)) {
      Optional<ReopenRequest> r = requests.lock(tenant, id);
      if (r.isEmpty() || !open(r.get())) {
        continue;
      }
      Set<UUID> profiles = scope.profilesOf(tenant, r.get());
      progress.seed(tenant, r.get(), profiles, SYSTEM_ACTOR);
      record(tenant, "THEO_DOI_HAU_KIEM", r.get());
      seeded++;
      if (profiles.isEmpty()) {
        log.warn("Yêu cầu mở lại {} không có hồ sơ trong phạm vi để theo dõi", r.get().code());
      }
    }
    return seeded;
  }

  /** EVT-10: chỉ nhận tính lại hậu kiểm; ghi lần tính lại mới nhất cho mọi yêu cầu đang mở. */
  @Transactional(TX)
  public void onRecomputed(
      TenantId tenant, UUID profileId, UUID scoreResultId, String jobKind, Instant at) {
    if (!RECOMPUTE_KIND.equals(jobKind) || profileId == null || at == null) {
      return;
    }
    update(tenant, profileId, p -> p.recomputed(scoreResultId, at));
  }

  /** EVT-17: biểu mẫu bắt buộc của hồ sơ trong phạm vi đã sinh. */
  @Transactional(TX)
  public void onFormGenerated(TenantId tenant, UUID formInstanceId, String formCode, Instant at) {
    if (formInstanceId == null || at == null || !requiredForms.contains(formCode)) {
      return;
    }
    Optional<FormRef> ref = forms.find(tenant, formInstanceId);
    if (ref.isEmpty()) {
      return;
    }
    update(tenant, ref.get().profileId(), p -> p.generated(formInstanceId, formCode, at));
  }

  /** EVT-18: biểu mẫu hồ sơ số đã ký ({@code sourceRefId} là mã bản biểu mẫu, suy luận). */
  @Transactional(TX)
  public void onSigned(TenantId tenant, String sourceModule, UUID sourceRefId, Instant signedAt) {
    if (!FORM_SOURCE.equals(sourceModule) || sourceRefId == null || signedAt == null) {
      return;
    }
    Optional<FormRef> ref = forms.find(tenant, sourceRefId);
    if (ref.isEmpty() || !requiredForms.contains(ref.get().formCode())) {
      return;
    }
    update(
        tenant, ref.get().profileId(), p -> p.signed(sourceRefId, ref.get().formCode(), signedAt));
  }

  /**
   * Hết hạn mở lại (MOD-08 báo trước khi tự khoá lại): đánh dấu "chưa hoàn tất hậu kiểm" khi còn hồ
   * sơ chưa xong. Thay đổi đã làm được giữ nguyên.
   */
  @Transactional(TX)
  public void onExpired(TenantId tenant, UUID reopenRequestId) {
    requests
        .lock(tenant, reopenRequestId)
        .filter(ReopenFollowUpService::open)
        .ifPresent(r -> markIncompleteIfPending(tenant, r));
  }

  /**
   * EVT-09 khoá lại: {@code sagaId} là mã yêu cầu mở lại (CORE-08-06). Kỳ khoá lần đầu hoặc yêu cầu
   * đã đóng thì bỏ qua.
   */
  @Transactional(TX)
  public void onRelocked(TenantId tenant, UUID sagaId, Instant lockedAt) {
    if (sagaId == null) {
      return;
    }
    Optional<ReopenRequest> found = requests.lock(tenant, sagaId);
    if (found.isEmpty() || !open(found.get())) {
      return;
    }
    ReopenRequest r = found.get();
    markIncompleteIfPending(tenant, r);
    Instant at = lockedAt == null ? clock.instant() : lockedAt;
    if (at.isBefore(r.executedAt())) {
      at = r.executedAt();
    }
    if (!requests.relocked(tenant, r, at, r.rowVersion(), SYSTEM_ACTOR)) {
      throw new IllegalStateException("Yêu cầu mở lại vừa bị thay đổi, xử lý lại EVT-09");
    }
    ReopenRequest closed = requests.find(tenant, r.id()).orElse(r);
    record(tenant, "DONG_YEU_CAU_MO_LAI", closed);
    for (UUID recipient : closeRecipients(tenant, closed)) {
      notices.followUpClosed(tenant, closed, recipient);
    }
  }

  private void update(
      TenantId tenant, UUID profileId, java.util.function.UnaryOperator<ReopenProgress> change) {
    seedExecuted(tenant);
    for (UUID requestId : progress.openRequestsOf(tenant, profileId)) {
      Optional<ReopenRequest> found = requests.lock(tenant, requestId);
      if (found.isEmpty() || !open(found.get())) {
        continue;
      }
      ReopenRequest r = found.get();
      List<ReopenProgress> rows = progress.of(tenant, r);
      List<ReopenProgress> after = new ArrayList<>(rows.size());
      for (ReopenProgress p : rows) {
        if (p.profileId().equals(profileId)) {
          ReopenProgress changed = change.apply(p);
          boolean done = changed.done(requiredForms);
          changed =
              changed.withCompletedAt(
                  done ? (p.completedAt() != null ? p.completedAt() : clock.instant()) : null);
          if (!changed.equals(p)) {
            progress.save(tenant, r, changed, SYSTEM_ACTOR);
          }
          after.add(changed);
        } else {
          after.add(p);
        }
      }
      relockIfComplete(tenant, r, after);
    }
  }

  private void relockIfComplete(TenantId tenant, ReopenRequest r, List<ReopenProgress> rows) {
    if (rows.isEmpty() || !rows.stream().allMatch(p -> p.completedAt() != null)) {
      return;
    }
    ReopenFollowUp f = progress.followUp(tenant, r);
    if (f.relockRequestedAt() != null) {
      return;
    }
    Instant now = clock.instant();
    progress.saveFollowUp(tenant, r, f.relockRequested(now, now), SYSTEM_ACTOR);
    record(tenant, "HOAN_TAT_HAU_KIEM", r);
    if (relock != null && !relock.finishEarly(tenant, r.id(), SYSTEM_ACTOR)) {
      log.info("Kỳ của yêu cầu {} đã bắt đầu khoá lại từ trước", r.code());
    }
  }

  private void markIncompleteIfPending(TenantId tenant, ReopenRequest r) {
    ReopenFollowUp f = progress.followUp(tenant, r);
    if (f.incomplete()) {
      return;
    }
    List<ReopenProgress> rows = progress.of(tenant, r);
    int pending = (int) rows.stream().filter(p -> p.completedAt() == null).count();
    if (!rows.isEmpty() && pending == 0) {
      return;
    }
    progress.saveFollowUp(tenant, r, f.markedIncomplete(clock.instant(), pending), SYSTEM_ACTOR);
    record(tenant, "HAU_KIEM_CHUA_HOAN_TAT", r);
    notices.followUpIncomplete(tenant, r, pending);
  }

  private Set<UUID> closeRecipients(TenantId tenant, ReopenRequest r) {
    Set<UUID> out = new LinkedHashSet<>();
    out.add(r.requestedBy());
    ReopenBasis basis = r.basis();
    if (complaints != null && basis != null && basis.kind() == ReopenBasis.Kind.KHIEU_NAI) {
      complaints
          .find(tenant, basis.id())
          .filter(c -> c.status() == ComplaintStatus.DANG_THI_HANH)
          .map(Complaint::handling)
          .map(h -> h.handlerId())
          .ifPresent(out::add);
    }
    return out;
  }

  private void record(TenantId tenant, String action, ReopenRequest r) {
    audit.record(tenant, SYSTEM_ACTOR, action, OBJECT_TYPE, r.id().toString(), null, null);
  }

  private static boolean open(ReopenRequest r) {
    return r.status() == ReopenStatus.DA_THUC_HIEN && r.relockedAt() == null;
  }
}
