package vn.viettel.okrkpi.org.application.port;

import java.util.UUID;

/**
 * DV-04-07 · Lệnh tới integration-gateway (SVC-13), nơi duy nhất nói chuyện với nguồn ngoài (BR-01-11) và giữ
 * {@code integ.mapping_xref}, {@code integ.dead_letter}. Lỗi kết nối ném {@link IntegrationUnavailableException} với lý do
 * tiếng Việt không chứa dữ liệu C2.
 */
public interface IntegrationGatewayPort {

  /** Yêu cầu gateway kéo dữ liệu nguồn cho lần chạy {@code runId}; gateway đẩy lô về org-service. */
  void requestRun(UUID tenantId, UUID runId, String connector);

  /** Đưa thư chết của lần chạy {@code runId} vào lại luồng xử lý; gateway đẩy lô về dưới lần chạy {@code retryRunId}. */
  void retryDeadLetters(UUID tenantId, UUID runId, UUID retryRunId, String connector);

  /** Ghi ánh xạ mã ngoài với người; ném {@code OrgConflictException} ORG-409-XREF-EXISTS khi mã đã gắn người khác. */
  void mapXref(UUID tenantId, String connector, String externalId, UUID personId);

  /** Ghi ánh xạ mã ngoài với đơn vị; ném {@code OrgConflictException} ORG-409-XREF-EXISTS khi mã đã gắn đơn vị khác. */
  void mapUnitXref(UUID tenantId, String connector, String externalId, UUID unitId);

  /** DV-13-10 · Yêu cầu đóng gói chuyển hồ sơ liên cell; {@code packageId} là khoá chống trùng ở gateway. */
  record CellTransferRequest(UUID packageId, UUID personId, String sourceTenantCode, String targetCell,
      UUID targetTenantId, String targetTenantCode) {
  }

  /** Gửi yêu cầu đóng gói, trả trạng thái gói ở gateway ({@code DONG_GOI}, {@code DA_GUI}...). */
  String requestCellTransfer(UUID tenantId, CellTransferRequest request);
}
