package vn.viettel.okrkpi.docgen.application.render;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng tới MOD-06 lấy danh sách biểu mẫu của gói hồ sơ và dữ liệu đã chuẩn hoá của từng biểu mẫu.
 * SVC-11 không đọc CSDL của module khác (TL-03 "Schema docgen"); MOD-06 kiểm trạng thái gói, phạm
 * vi của người gọi và trả băm dữ liệu {@code data_snapshot_hash} (suy luận về cổng, wiki trang 14).
 */
public interface RenderInputPort {

  /**
   * Biểu mẫu cần sinh của gói; rỗng khi gói không tồn tại hoặc ngoài phạm vi của người gọi. MOD-06
   * ném {@code BusinessException} {@code DOSSIER-409-*} khi gói không ở trạng thái lập hồ sơ.
   *
   * @param formCodes chỉ các mẫu này; rỗng là đủ danh mục theo Policy Pack
   */
  Optional<List<FormRef>> forms(UUID tenantId, UUID actor, UUID packageId, List<String> formCodes);

  /**
   * Dữ liệu đã chuẩn hoá của một biểu mẫu: tài liệu JSON chuẩn tắc mà băm SHA-256 của nó là {@code
   * data_snapshot_hash}; rỗng khi không còn biểu mẫu.
   */
  Optional<FormData> data(UUID tenantId, UUID formInstanceId);

  /**
   * Một biểu mẫu cần sinh.
   *
   * @param templateVersion phiên bản mẫu mà Policy Pack gắn với kỳ của gói chỉ định ({@code
   *     policy_binding}); {@code null} thì dùng phiên bản đang hiệu lực mới nhất (DV-11-04)
   * @param policyPackCode mã Policy Pack gắn với kỳ, đóng tem vào tệp (BR-11-03)
   * @param policyPackVersion phiên bản Policy Pack gắn với kỳ, đóng tem vào tệp
   */
  record FormRef(
      UUID formInstanceId,
      String formCode,
      String subjectGroupCode,
      byte[] dataSnapshotHash,
      Integer templateVersion,
      String policyPackCode,
      String policyPackVersion) {

    public FormRef(
        UUID formInstanceId, String formCode, String subjectGroupCode, byte[] dataSnapshotHash) {
      this(formInstanceId, formCode, subjectGroupCode, dataSnapshotHash, null, null, null);
    }
  }

  /**
   * Dữ liệu một biểu mẫu.
   *
   * @param canonicalJson tài liệu JSON chuẩn tắc đúng như MOD-06 đã băm
   */
  record FormData(UUID formInstanceId, String formCode, byte[] canonicalJson) {}
}
