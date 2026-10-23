package vn.viettel.okrkpi.shared.docgen;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nguồn dữ liệu xem trước văn bản do module khác MOD-06 sở hữu, dùng cho cổng nội bộ {@code POST
 * /internal/v1/dossier-form-previews} ({@code sourceModule} khác {@code appraisal} và {@code
 * dossier}). Đặt ở {@code shared} để MOD-06 không phụ thuộc trực tiếp module nguồn (ma trận phụ
 * thuộc TL-01 không cho {@code dossier} gọi {@code oversight}); module nguồn tự kiểm quyền đọc của
 * người dùng (suy luận, wiki trang 14).
 */
public interface FormPreviewSource {

  /** Giá trị {@code sourceModule} mà nguồn này phục vụ, ví dụ {@code oversight}. */
  String module();

  /**
   * Dữ liệu hiện tại của bản ghi nguồn; rỗng khi không có hoặc người dùng không được đọc.
   *
   * @param actor người dùng yêu cầu xem trước
   */
  Optional<Source> find(TenantId tenant, UUID actor, UUID sourceRefId);

  /**
   * @param profileId hồ sơ đánh giá liên quan, để MOD-06 chọn Policy Pack và phiên bản mẫu; có thể
   *     rỗng
   * @param canonicalJson dữ liệu JSON chuẩn tắc (khoá sắp theo thứ tự), UTF-8
   */
  record Source(UUID profileId, byte[] canonicalJson) {}
}
