package vn.viettel.okrkpi.workflow.domain.template;

/**
 * Vòng đời phiên bản mẫu luồng {@code flowable.wf_template.status} mở rộng theo BS-01 SCR-1505
 * "Vòng đời phiên bản": {@code NHAP} → {@code DAT_KIEM_THU} → {@code CHO_PHE_DUYET} → {@code
 * HIEU_LUC} → {@code NGUNG}.
 */
public enum TemplateStatus {
  NHAP,
  DAT_KIEM_THU,
  CHO_PHE_DUYET,
  HIEU_LUC,
  NGUNG;

  /** Còn sửa được nội dung (sửa sau khi đạt kiểm thử đưa mẫu về {@code NHAP}, suy luận). */
  public boolean editable() {
    return this == NHAP || this == DAT_KIEM_THU;
  }

  /** Đã phát hành: nội dung bất biến. */
  public boolean released() {
    return this == HIEU_LUC || this == NGUNG;
  }
}
