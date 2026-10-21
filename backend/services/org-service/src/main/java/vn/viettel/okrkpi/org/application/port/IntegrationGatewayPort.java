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
}
