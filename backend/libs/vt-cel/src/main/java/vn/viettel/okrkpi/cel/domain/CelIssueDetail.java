package vn.viettel.okrkpi.cel.domain;

import java.util.Objects;

/**
 * Chi tiết lỗi biên dịch/kiểm kiểu biểu thức CEL kèm vị trí dòng, cột và phân loại lỗi để phục vụ
 * mã lỗi chuẩn {@code POLICY-422-CEL-TYPE}.
 *
 * @param line dòng xảy ra lỗi (bắt đầu từ 1, hoặc -1 nếu lỗi cấp toàn biểu thức)
 * @param column cột xảy ra lỗi (bắt đầu từ 0 hoặc 1, hoặc -1 nếu không xác định)
 * @param errorKind phân loại lỗi ({@code SYNTAX}, {@code TYPE_MISMATCH}, {@code UNDECLARED_IDENT},
 *     {@code RETURN_TYPE_MISMATCH}, {@code AST_DEPTH_EXCEEDED}, {@code EXPRESSION_TOO_LONG})
 * @param message thông điệp mô tả lỗi
 */
public record CelIssueDetail(int line, int column, String errorKind, String message) {

  public CelIssueDetail {
    Objects.requireNonNull(errorKind, "errorKind must not be null");
    Objects.requireNonNull(message, "message must not be null");
  }
}
