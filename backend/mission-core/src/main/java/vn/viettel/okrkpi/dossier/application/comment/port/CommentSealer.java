package vn.viettel.okrkpi.dossier.application.comment.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mã hoá phong bì nội dung góp ý C2 (TL-03 "Mã hoá phong bì cho cột C2", ADR-06): DEK mục đích
 * {@code GOP_Y_KIEM_DIEM}, AAD gồm tenant, bảng, cột và mã dòng. Không có bộ mã hoá thì từ chối
 * ghi, không bao giờ lưu nội dung rõ.
 */
public interface CommentSealer {

  /** Bản mã và khoá dữ liệu đã dùng. */
  record Sealed(byte[] cipher, UUID dekId) {}

  Sealed seal(TenantId tenant, UUID commentId, String plain);

  String open(TenantId tenant, UUID commentId, byte[] cipher);
}
