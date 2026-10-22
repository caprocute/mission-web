package vn.viettel.okrkpi.oversight.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexReader;
import vn.viettel.okrkpi.oversight.application.port.OversightAuditLog;
import vn.viettel.okrkpi.oversight.domain.AccessLogQuery;
import vn.viettel.okrkpi.oversight.domain.AuditDocument;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code searchAccessLogs} (CORE-07-12, TL-01 G6, BR-12-10, BR-16-04): cán bộ thanh tra ({@code
 * inspector}, AAL2) tra lần xem dữ liệu C2 trong tenant từ chỉ mục kiểm toán chỉ đọc. Kiểm vai trò
 * ở đây chỉ là lớp phòng thủ thứ hai sau OPA. Mỗi lần tra cứu ghi đúng một sự kiện {@code
 * XEM_NHAT_KY_KIEM_TOAN} lên {@code sys.audit.v1} trong giao dịch của schema oversight.
 */
public class AccessLogSearchService {

  static final String TX = "oversightTransactionManager";
  static final String ROLE = "inspector";
  static final String OPERATION = "searchAccessLogs";
  static final String OBJECT_TYPE = "oversight.access_log";

  private static final Pattern OBJECT_TYPE_PATTERN = Pattern.compile("^[a-z_]+\\.[a-z_]+$");
  private static final Pattern OBJECT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

  private final AuditIndexReader reader;
  private final OversightAuditLog audit;

  public AccessLogSearchService(AuditIndexReader reader, OversightAuditLog audit) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  /**
   * Một trang nhật ký, mới nhất trước.
   *
   * @param fetch số tài liệu cần lấy (thường là cỡ trang cộng một để biết còn trang sau)
   */
  @Transactional(TX)
  public List<AuditDocument> search(
      TenantId tenantId,
      Searcher searcher,
      AccessLogQuery query,
      AuditIndexReader.After after,
      int fetch) {
    if (!searcher.roles().contains(ROLE)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Chỉ cán bộ thanh tra được tra cứu nhật ký truy cập.");
    }
    if (!StepUpRequiredException.REQUIRED_ACR.equals(
        AuthzSubjectResolver.normalizeAcr(searcher.acr()))) {
      throw new StepUpRequiredException(
          OPERATION, 900, "Tra cứu nhật ký truy cập cần xác thực nhiều lớp.");
    }
    if (query.objectType() != null && !OBJECT_TYPE_PATTERN.matcher(query.objectType()).matches()) {
      throw invalid("objectType", "Loại đối tượng không hợp lệ.");
    }
    if (query.objectId() != null && !OBJECT_ID_PATTERN.matcher(query.objectId()).matches()) {
      throw invalid("objectId", "Mã đối tượng không hợp lệ.");
    }
    List<AuditDocument> found = reader.searchAccess(tenantId, query, after, fetch);
    audit.record(
        tenantId,
        searcher.userId(),
        AuditDocument.ACTION_SEARCH,
        OBJECT_TYPE,
        summary(query),
        null,
        ROLE);
    return found;
  }

  /** Tóm tắt điều kiện bằng mã để sổ cái biết ai tra cứu điều gì, không chép văn bản tự do. */
  static String summary(AccessLogQuery q) {
    StringBuilder s = new StringBuilder();
    append(s, "nguoiXem", q.actorId() == null ? null : q.actorId().toString());
    append(s, "loai", q.objectType());
    append(s, "doiTuong", q.objectId());
    if (q.othersOnly()) {
      append(s, "nguoiKhac", "1");
    }
    append(s, "tu", q.from() == null ? null : q.from().toString());
    append(s, "den", q.to() == null ? null : q.to().toString());
    return s.isEmpty() ? "*" : s.toString();
  }

  private static void append(StringBuilder s, String key, String value) {
    if (value != null) {
      if (!s.isEmpty()) {
        s.append(';');
      }
      s.append(key).append('=').append(value);
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  /** Người tra cứu: mã, vai trò và mức xác thực của phiên. */
  public record Searcher(UUID userId, List<String> roles, String acr) {
    public Searcher {
      Objects.requireNonNull(userId, "userId");
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }
}
