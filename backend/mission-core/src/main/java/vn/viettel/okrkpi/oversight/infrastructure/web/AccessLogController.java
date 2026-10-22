package vn.viettel.okrkpi.oversight.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.oversight.application.AccessLogSearchService;
import vn.viettel.okrkpi.oversight.application.AccessLogSearchService.Searcher;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexReader;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexUnavailableException;
import vn.viettel.okrkpi.oversight.domain.AccessLogQuery;
import vn.viettel.okrkpi.oversight.domain.AuditDocument;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code GET /api/v1/oversight/access-logs} ({@code searchAccessLogs}, CORE-07-12, QT-12, QT-16):
 * nhật ký xem dữ liệu C2 của tenant hiện tại, mới nhất trước, phân trang con trỏ theo {@code
 * (occurredAt, docId)}. Khi cell chưa cấu hình chỉ mục kiểm toán hoặc chỉ mục không truy cập được,
 * trả 503 {@code OVERSIGHT-503-AUDIT-INDEX-UNAVAILABLE}. Trường không có giá trị bị lược khỏi phản
 * hồi.
 */
@RestController
@RequestMapping("/api/v1/oversight/access-logs")
public class AccessLogController {

  static final String UNAVAILABLE = "OVERSIGHT-503-AUDIT-INDEX-UNAVAILABLE";
  private static final String SCOPE = "access-logs";

  private final ObjectProvider<AccessLogSearchService> service;
  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<OversightCaller> custom;
  private final CursorCodec cursors;
  private final ObjectMapper json;

  public AccessLogController(
      ObjectProvider<AccessLogSearchService> service,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<OversightCaller> custom,
      CursorCodec cursors,
      ObjectProvider<ObjectMapper> json) {
    this.service = Objects.requireNonNull(service, "service");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.custom = Objects.requireNonNull(custom, "custom");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.json = json.getIfAvailable(ObjectMapper::new);
  }

  @GetMapping
  @RequiresPermission(operationId = "searchAccessLogs", module = "oversight")
  public JsonNode searchAccessLogs(
      @RequestParam(value = "actor", required = false) UUID actor,
      @RequestParam(value = "objectType", required = false) String objectType,
      @RequestParam(value = "objectId", required = false) String objectId,
      @RequestParam(value = "othersOnly", required = false, defaultValue = "false")
          boolean othersOnly,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    Searcher searcher = searcher();
    AccessLogQuery query = query(actor, objectType, objectId, othersOnly, from, to);
    PageRequest page = cursors.pageRequest(limit, cursor, SCOPE);
    AuditIndexReader.After after =
        page.afterKey()
            .map(k -> new AuditIndexReader.After(k.getInstant(0), k.getString(1)))
            .orElse(null);
    AccessLogSearchService search = service.getIfAvailable();
    if (search == null) {
      throw unavailable();
    }
    TenantId tenantId = TenantContext.require();
    List<AuditDocument> fetched;
    try {
      fetched = search.search(tenantId, searcher, query, after, page.fetchSize());
    } catch (AuditIndexUnavailableException e) {
      throw unavailable();
    }
    CursorPage<AuditDocument> result =
        cursors.page(fetched, page, d -> CursorKey.of(d.occurredAt(), d.docId()));
    ObjectNode body = json.createObjectNode();
    ArrayNode items = body.putArray("items");
    for (AuditDocument d : result.items()) {
      items.add(toDto(d));
    }
    if (result.nextCursor() == null) {
      body.putNull("nextCursor");
    } else {
      body.put("nextCursor", result.nextCursor());
    }
    return body;
  }

  private ObjectNode toDto(AuditDocument d) {
    ObjectNode o = json.createObjectNode();
    o.put("eventId", d.eventId().toString());
    o.put("occurredAt", d.occurredAt().atOffset(ZoneOffset.UTC).toString());
    if (d.actorId() != null) {
      o.put("actorId", d.actorId().toString());
    }
    putIfPresent(o, "actorRole", d.actorRole());
    if (d.actorAssignmentId() != null) {
      o.put("actorAssignmentId", d.actorAssignmentId().toString());
    }
    o.put("objectType", d.objectType());
    o.put("objectId", d.objectId());
    o.put("column", d.column());
    putIfPresent(o, "purpose", d.purpose());
    if (d.self() != null) {
      o.put("self", d.self());
    }
    o.put("system", d.system());
    putIfPresent(o, "traceparent", d.traceparent());
    return o;
  }

  private static void putIfPresent(ObjectNode o, String field, String value) {
    if (value != null) {
      o.put(field, value);
    }
  }

  private Searcher searcher() {
    OversightCaller caller = custom.getIfAvailable();
    if (caller != null) {
      return new Searcher(
          caller.currentUserId().orElseThrow(AccessLogController::unauthorized),
          caller.roles(),
          caller.acr().orElse(null));
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw unauthorized();
    }
    try {
      return new Searcher(UUID.fromString(subject.userId()), subject.roles(), subject.acr());
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  private static AccessLogQuery query(
      UUID actor, String objectType, String objectId, boolean othersOnly, String from, String to) {
    Instant start = instant("from", from);
    Instant end = instant("to", to);
    try {
      return new AccessLogQuery(actor, blank(objectType), blank(objectId), othersOnly, start, end);
    } catch (IllegalArgumentException e) {
      throw invalid("to", "Thời điểm kết thúc phải sau thời điểm bắt đầu.");
    }
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static Instant instant(String field, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      throw invalid(field, "Thời điểm không đúng định dạng.");
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static BusinessException unavailable() {
    return new BusinessException(
        UNAVAILABLE, "Chưa tra cứu được nhật ký truy cập, vui lòng thử lại sau.");
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
