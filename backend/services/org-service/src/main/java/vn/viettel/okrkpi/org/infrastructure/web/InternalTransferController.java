package vn.viettel.okrkpi.org.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.transfer.CrossCellTransferRecorder;
import vn.viettel.okrkpi.org.application.transfer.CrossCellTransferService;
import vn.viettel.okrkpi.org.application.transfer.WorkHistoryTransferService;
import vn.viettel.okrkpi.org.application.transfer.WorkHistoryTransferService.WorkHistory;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/**
 * DV-13-10 · Cổng nội bộ của SVC-13 cho gói chuyển hồ sơ liên cell, phần "quá trình công tác" (cấu hình
 * {@code okrkpi.integ.cell-transfer.parts[QUA_TRINH_CONG_TAC]} của gateway). Cell nguồn:
 * {@code GET /internal/v1/org/transfer-parts/{personId}/work-history}, 404 khi chưa có phân công. Cell đích:
 * {@code POST /internal/v1/org/transfer-imports/{personId}?kind=QUA_TRINH_CONG_TAC}, thân là nội dung phần,
 * {@code Idempotency-Key} là {@code <mã gói>:<tên phần>}; 201 khi nhập mới, 200 khi đã nhập cùng nội dung, 409 khi
 * đã nhập nội dung khác, 422 khi phần không hợp lệ. Chỉ token dịch vụ có vai trò
 * {@link InternalSyncController#SERVICE_ROLE} gọi được; tenant lấy từ {@code X-Tenant-Id} vì token dịch vụ không mang
 * tenant (suy luận, hợp đồng nội bộ chưa có trong {@code contracts/openapi}). Điều động liên cell đã duyệt:
 * {@code POST /internal/v1/org/cross-cell-transfers}, vai trò gọi theo {@code okrkpi.org.cross-cell.caller-roles}.
 */
@RestController
@RequestMapping("/internal/v1/org")
@ConditionalOnWebApplication
public class InternalTransferController {

  static final String TENANT_HEADER = "X-Tenant-Id";

  /** Đường ghi điều động liên cell đã duyệt. */
  public static final String CROSS_CELL = "/internal/v1/org/cross-cell-transfers";

  private final WorkHistoryTransferService transfers;
  private final CrossCellTransferService crossCell;
  private final HttpServletRequest request;

  public InternalTransferController(WorkHistoryTransferService transfers, CrossCellTransferService crossCell,
      HttpServletRequest request) {
    this.transfers = transfers;
    this.crossCell = crossCell;
    this.request = request;
  }

  /** Điều động liên cell đã được cấp có thẩm quyền duyệt. */
  public record CrossCellRequest(UUID personId, String orderNo, LocalDate orderDate, LocalDate effectiveOn,
      String signedDocRef, String sourceTenantCode, String targetCell, UUID targetTenantId, String targetTenantCode) {
  }

  /**
   * Ghi quyết định điều động liên cell, đóng phân công từ ngày hiệu lực và yêu cầu SVC-13 đóng gói hồ sơ: 202 kèm
   * {@code Location} của gói ở gateway; gọi lại cùng yêu cầu trả cùng mã gói.
   */
  @PostMapping("/cross-cell-transfers")
  public ResponseEntity<CrossCellTransferService.Result> crossCell(@RequestHeader(TENANT_HEADER) UUID tenantId,
      @RequestBody CrossCellRequest body) {
    if (body == null) {
      return ResponseEntity.badRequest().build();
    }
    CrossCellTransferRecorder.Command cmd = new CrossCellTransferRecorder.Command(body.personId(), body.orderNo(),
        body.orderDate(), body.effectiveOn(), body.signedDocRef(), body.sourceTenantCode(), body.targetCell(),
        body.targetTenantId(), body.targetTenantCode());
    CrossCellTransferService.Result r = inTenant(tenantId, () -> crossCell.transfer(actor(tenantId), cmd));
    return ResponseEntity.accepted().location(URI.create("/internal/v1/integ/cell-transfers/" + r.packageId()))
        .body(r);
  }

  private Actor actor(UUID tenant) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    UUID subject = InternalSyncController.SYSTEM_ACTOR;
    if (auth != null) {
      try {
        subject = UUID.fromString(auth.getName());
      } catch (IllegalArgumentException e) {
        subject = InternalSyncController.SYSTEM_ACTOR;
      }
    }
    return new Actor(tenant, subject, Set.of(), "aal1", Instant.now(), null, request.getHeader("traceparent"));
  }

  @GetMapping("/transfer-parts/{personId}/work-history")
  public ResponseEntity<WorkHistory> workHistory(@RequestHeader(TENANT_HEADER) UUID tenantId,
      @PathVariable UUID personId) {
    return inTenant(tenantId, () -> transfers.collect(personId).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build()));
  }

  @PostMapping("/transfer-imports/{personId}")
  public ResponseEntity<Void> importPart(@RequestHeader(TENANT_HEADER) UUID tenantId, @PathVariable UUID personId,
      @RequestParam("kind") String kind, @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader("X-Transfer-Package") UUID packageId, @RequestHeader("X-Source-Tenant") String sourceTenant,
      @RequestHeader("Content-Type") String mediaType, @RequestBody byte[] content) {
    String prefix = packageId + ":";
    if (!idempotencyKey.startsWith(prefix)) {
      return ResponseEntity.unprocessableEntity().build();
    }
    WorkHistoryTransferService.Part part = new WorkHistoryTransferService.Part(packageId,
        idempotencyKey.substring(prefix.length()), kind, sourceTenant, personId, mediaType, content);
    try {
      return inTenant(tenantId, () -> ResponseEntity
          .status(transfers.importPart(part, InternalSyncController.SYSTEM_ACTOR) ? HttpStatus.CREATED : HttpStatus.OK)
          .build());
    } catch (WorkHistoryTransferService.InvalidPartException e) {
      return ResponseEntity.unprocessableEntity().build();
    } catch (WorkHistoryTransferService.ConflictException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
  }

  private static <T> T inTenant(UUID tenant, Supplier<T> work) {
    TenantContextHolder.set(tenant);
    try {
      return work.get();
    } finally {
      TenantContextHolder.clear();
    }
  }
}
