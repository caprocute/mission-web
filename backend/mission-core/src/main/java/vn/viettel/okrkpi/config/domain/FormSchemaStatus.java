package vn.viettel.okrkpi.config.domain;

/**
 * Trạng thái biểu mẫu động ({@code config.form_schema.status}, CORE-08-13): {@code NHAP} soạn được;
 * {@code DA_PHAT_HANH} khi UI Pack chứa nó được phát hành; {@code NGUNG} khi không còn dùng.
 */
public enum FormSchemaStatus {
  NHAP,
  DA_PHAT_HANH,
  NGUNG
}
