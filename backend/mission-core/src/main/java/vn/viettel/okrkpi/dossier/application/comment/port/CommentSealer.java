package vn.viettel.okrkpi.dossier.application.comment.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mã hoá phong bì nội dung C2 của kiểm điểm (TL-03 "Mã hoá phong bì cho cột C2", ADR-06): DEK mục
 * đích {@code GOP_Y_KIEM_DIEM}, AAD gồm tenant, bảng, cột và mã dòng. Dùng cho góp ý ({@code
 * review_comment.comment_enc}) và hạn chế cần khắc phục ({@code remediation_item.content_enc}).
 * Không có bộ mã hoá thì từ chối ghi, không bao giờ lưu nội dung rõ.
 */
public interface CommentSealer {

  String COMMENT_COLUMN = "dossier.review_comment.comment_enc";
  String REMEDIATION_COLUMN = "dossier.remediation_item.content_enc";

  /** Bản mã và khoá dữ liệu đã dùng. */
  record Sealed(byte[] cipher, UUID dekId) {}

  /** {@code column} là tên đủ {@code schema.bảng.cột} đưa vào AAD. */
  Sealed seal(TenantId tenant, String column, UUID rowId, String plain);

  String open(TenantId tenant, String column, UUID rowId, byte[] cipher);

  default Sealed seal(TenantId tenant, UUID commentId, String plain) {
    return seal(tenant, COMMENT_COLUMN, commentId, plain);
  }

  default String open(TenantId tenant, UUID commentId, byte[] cipher) {
    return open(tenant, COMMENT_COLUMN, commentId, cipher);
  }
}
