package vn.viettel.okrkpi.docgen.application.render;

import java.util.Objects;
import java.util.UUID;

/**
 * Bối cảnh một lượt trộn: biểu mẫu nguồn, phiên bản mẫu và Policy Pack gắn với kỳ, cờ bản nháp. Bộ
 * ánh xạ dùng để điền tem phiên bản ở chân trang và để từ chối dữ liệu không thuộc phiên bản bộ quy
 * tắc đã chốt (bảng chấm điểm, DV-11-03).
 *
 * @param formInstanceId biểu mẫu nguồn; với xem trước là bản ghi nguồn ({@code sourceRefId})
 * @param policyPackCode mã Policy Pack gắn với kỳ, {@code null} khi lệnh sinh không mang
 * @param policyPackVersion phiên bản Policy Pack gắn với kỳ, {@code null} khi lệnh sinh không mang
 */
public record RenderContext(
    UUID tenantId,
    UUID formInstanceId,
    String formCode,
    int templateVersion,
    String policyPackCode,
    String policyPackVersion,
    boolean draft) {

  public RenderContext {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(formInstanceId, "formInstanceId");
    Objects.requireNonNull(formCode, "formCode");
  }
}
