package vn.viettel.okrkpi.org.infrastructure.web;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.org.application.sync.NationalIdMatchService;
import vn.viettel.okrkpi.org.application.sync.NationalIdMatchService.Match;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/**
 * Cổng nội bộ chỉ đọc của DV-02-04 (không qua APISIX): authenticator liên kết danh tính VNeID trong Keycloak (SVC-02)
 * hỏi người nào của tenant có số định danh đã cho. Chỉ token dịch vụ có vai trò {@link #SERVICE_ROLE} gọi được. Số
 * định danh đi trong thân POST để không nằm trong URL hay nhật ký truy cập (suy luận, hợp đồng nội bộ chưa có trong
 * {@code contracts/openapi}).
 */
@RestController
@RequestMapping("/internal/v1/org")
@ConditionalOnWebApplication
public class InternalIdentityController {

  /** Vai trò realm của tài khoản dịch vụ môi giới danh tính của Keycloak. */
  public static final String SERVICE_ROLE = "svc_iam";

  static final String MATCH_PATH = "/tenants/{tenantId}/persons/national-id-match";

  /** Thân yêu cầu; số định danh là C2, không ghi nhật ký ({@link #toString()} che giá trị). */
  public record MatchRequest(String nationalId) {
    @Override
    public String toString() {
      return "MatchRequest[nationalId=***]";
    }
  }

  private final NationalIdMatchService service;

  public InternalIdentityController(NationalIdMatchService service) {
    this.service = service;
  }

  @PostMapping(MATCH_PATH)
  public ResponseEntity<Match> match(@PathVariable UUID tenantId, @RequestBody MatchRequest body) {
    if (body == null || body.nationalId() == null || body.nationalId().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    TenantContextHolder.set(tenantId);
    try {
      return ResponseEntity.ok(service.match(tenantId, body.nationalId()));
    } finally {
      TenantContextHolder.clear();
    }
  }
}
