package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.dossiers.model.DossierComment;
import vn.viettel.okrkpi.contract.dossiers.model.DossierCommentCreate;
import vn.viettel.okrkpi.contract.dossiers.model.DossierCommentKind;
import vn.viettel.okrkpi.contract.dossiers.model.DossierCommentPage;
import vn.viettel.okrkpi.contract.dossiers.model.DossierCommentPhase;
import vn.viettel.okrkpi.contract.dossiers.model.DossierCommentSource;
import vn.viettel.okrkpi.contract.dossiers.model.Ref;
import vn.viettel.okrkpi.dossier.application.comment.ReviewCommentService;
import vn.viettel.okrkpi.dossier.application.comment.ReviewCommentService.CommentPage;
import vn.viettel.okrkpi.dossier.application.comment.ReviewCommentService.CommentView;
import vn.viettel.okrkpi.dossier.application.comment.ReviewCommentService.Draft;
import vn.viettel.okrkpi.dossier.application.comment.port.ReviewCommentRepository.Position;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Góp ý kiểm điểm của {@code dossiers.yaml} (CORE-06-03, QT-11 bước 6–7, SCR-1101 vùng 2): {@code
 * listDossierMeetingComments}, {@code createDossierMeetingComment}. Nội dung C2: thao tác khai mức
 * {@code C2} để lớp OPA ghi nhật ký truy cập C2 (CORE-00-05) và trả cờ ẩn danh; nội dung không bao
 * giờ vào nhật ký ứng dụng.
 */
@RestController
@RequestMapping("/api/v1/dossiers/meetings/{id}/comments")
public class DossierCommentController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;
  private static final Set<String> KINDS = Set.of("UU_DIEM", "HAN_CHE", "KIEN_NGHI");

  private final ReviewCommentService service;
  private final DossierCallers callers;

  public DossierCommentController(
      ReviewCommentService service,
      ObjectProvider<DossierCaller> callers,
      ObjectProvider<AuthzSubjectResolver> subjects) {
    this.service = Objects.requireNonNull(service, "service");
    this.callers = new DossierCallers(callers, subjects);
  }

  @GetMapping
  @RequiresPermission(
      operationId = "listDossierMeetingComments",
      module = "dossiers",
      resourceType = "review_comment",
      classification = "C2")
  public ResponseEntity<DossierCommentPage> listDossierMeetingComments(
      @PathVariable("id") UUID id,
      @RequestParam(value = "phase", required = false) String phase,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    if (phase != null
        && !ReviewCommentService.TRUOC_HOI_NGHI.equals(phase)
        && !ReviewCommentService.HOI_NGHI.equals(phase)) {
      throw invalid("phase", "Giai đoạn góp ý không hợp lệ.");
    }
    if (kind != null && !KINDS.contains(kind)) {
      throw invalid("kind", "Loại nội dung góp ý không hợp lệ.");
    }
    CommentPage page;
    try {
      page =
          service.list(
              tenant,
              callers.actor(),
              callers.unit().orElse(null),
              id,
              phase,
              kind,
              decodeCursor(cursor),
              size + 1);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    boolean more = page.items().size() > size;
    List<CommentView> rows = more ? page.items().subList(0, size) : page.items();
    List<DossierComment> items = new ArrayList<>();
    rows.forEach(v -> items.add(comment(v)));
    String next = more ? encodeCursor(rows.get(rows.size() - 1)) : null;
    DossierCommentPage body = new DossierCommentPage(items, next);
    body.setAnonymous(page.anonymous());
    return ResponseEntity.ok(body);
  }

  @PostMapping
  @Idempotent
  @RequiresPermission(
      operationId = "createDossierMeetingComment",
      module = "dossiers",
      resourceType = "review_comment",
      classification = "C2")
  public ResponseEntity<DossierComment> createDossierMeetingComment(
      @PathVariable("id") UUID id, @RequestBody DossierCommentCreate body) {
    TenantId tenant = TenantContext.require();
    CommentView view;
    try {
      view =
          service.create(
              tenant,
              callers.actor(),
              id,
              new Draft(
                  body.getSubjectPersonId(),
                  body.getKind() == null ? null : body.getKind().getValue(),
                  body.getPhase() == null ? null : body.getPhase().getValue(),
                  body.getContent()));
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/dossiers/meetings/" + id + "/comments/" + view.row().id()))
        .body(comment(view));
  }

  static DossierComment comment(CommentView v) {
    DossierComment c =
        new DossierComment(
            v.row().id(),
            v.row().meetingId(),
            v.row().subjectPersonId(),
            DossierCommentKind.fromValue(v.row().kind()),
            DossierCommentPhase.fromValue(v.row().phase()),
            v.content(),
            v.row().createdAt().atZone(ReviewMeetingService.ZONE).toOffsetDateTime());
    c.setCommenter(v.commenterHidden() ? null : new Ref(v.row().commenterPersonId(), ""));
    c.setSource(DossierCommentSource.fromValue(v.row().source()));
    return c;
  }

  static String encodeCursor(CommentView last) {
    String raw = last.row().createdAt().toString() + "|" + last.row().id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
  }

  static Position decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
      int bar = raw.indexOf('|');
      return new Position(
          Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
    } catch (RuntimeException e) {
      throw invalid("cursor", "Con trỏ trang không hợp lệ.");
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
