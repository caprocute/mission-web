package vn.viettel.okrkpi.org.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.sync.DiscrepancyService;
import vn.viettel.okrkpi.org.application.sync.SourceRecord;
import vn.viettel.okrkpi.org.application.sync.SyncIngestService;
import vn.viettel.okrkpi.org.application.sync.SyncIngestService.BatchResult;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/**
 * Cổng nội bộ của DV-04-07 (không thuộc API công khai, không qua APISIX): integration-gateway (SVC-13) đẩy lô bản ghi
 * nhân sự đã chuẩn hoá của một lần đồng bộ; mission-core hỏi số chênh lệch mức cao chưa xử lý cho BR-01-10 trước khi
 * khoá kỳ. Chỉ token dịch vụ có vai trò {@link #SERVICE_ROLE} gọi được (chuỗi bảo mật {@code /internal/**}); tenant
 * lấy từ thân hoặc đường dẫn vì token dịch vụ không mang tenant (suy luận, hợp đồng nội bộ chưa có trong
 * {@code contracts/openapi}).
 */
@RestController
@RequestMapping("/internal/v1/org")
@ConditionalOnWebApplication
public class InternalSyncController {

  /** Vai trò realm của tài khoản dịch vụ được gọi cổng nội bộ. */
  public static final String SERVICE_ROLE = "svc_integration_gateway";

  /** Mã chủ thể ghi vào cột kiểm toán khi token dịch vụ không mang {@code sub} dạng UUID. */
  static final UUID SYSTEM_ACTOR = UUID.fromString("01960000-0000-7000-8000-00000000f013");

  private final SyncIngestService ingest;
  private final DiscrepancyService discrepancies;
  private final HttpServletRequest request;

  public InternalSyncController(SyncIngestService ingest, DiscrepancyService discrepancies,
      HttpServletRequest request) {
    this.ingest = ingest;
    this.discrepancies = discrepancies;
    this.request = request;
  }

  /** Thân một lô: {@code last} đóng lần chạy kèm số thư chết và lý do lỗi kết nối (nếu có). */
  public record BatchRequest(UUID tenantId, String connector, List<RecordDto> records, boolean last,
      int deadLetters, String reason) {
  }

  /** Bản ghi nguồn; số định danh và ngày sinh là C2, không ghi nhật ký. */
  public record RecordDto(String externalId, UUID mappedPersonId, String fullName, String nationalId,
      LocalDate birthDate, String employmentStatus, boolean partyMember, List<PostDto> posts,
      LocalDate effectiveFrom) {
  }

  /** Phân công ở nguồn. */
  public record PostDto(UUID orgUnitId, UUID jobTitleId, String kind, boolean head, LocalDate validFrom) {
  }

  @PostMapping("/sync-runs/{runId}/batches")
  public ResponseEntity<BatchResult> batch(@PathVariable UUID runId, @RequestBody BatchRequest body) {
    if (body.tenantId() == null || body.connector() == null) {
      return ResponseEntity.badRequest().build();
    }
    List<SourceRecord> records = body.records() == null ? List.of() : body.records().stream()
        .map(r -> new SourceRecord(r.externalId(), r.mappedPersonId(), r.fullName(), r.nationalId(), r.birthDate(),
            r.employmentStatus(), r.partyMember(), r.posts() == null ? List.of() : r.posts().stream()
                .map(p -> new SourceRecord.SourcePost(p.orgUnitId(), p.jobTitleId(), p.kind(), p.head(),
                    p.validFrom())).toList(), r.effectiveFrom()))
        .toList();
    return inTenant(body.tenantId(), () -> ingest.ingest(actor(body.tenantId()), runId, body.connector(), records,
        body.last(), body.deadLetters(), body.reason()));
  }

  @GetMapping("/tenants/{tenantId}/discrepancies/high-count")
  public ResponseEntity<Map<String, Long>> highCount(@PathVariable UUID tenantId) {
    return inTenant(tenantId, () -> Map.of("open", discrepancies.countOpenHigh()));
  }

  private static <T> ResponseEntity<T> inTenant(UUID tenant, java.util.function.Supplier<T> work) {
    TenantContextHolder.set(tenant);
    try {
      return ResponseEntity.ok(work.get());
    } finally {
      TenantContextHolder.clear();
    }
  }

  private Actor actor(UUID tenant) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    UUID subject = SYSTEM_ACTOR;
    if (auth != null) {
      try {
        subject = UUID.fromString(auth.getName());
      } catch (IllegalArgumentException e) {
        subject = SYSTEM_ACTOR;
      }
    }
    return new Actor(tenant, subject, Set.of(SERVICE_ROLE), "aal1", Instant.now(), null,
        request.getHeader("traceparent"));
  }
}
