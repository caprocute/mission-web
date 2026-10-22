package vn.viettel.okrkpi.docgen.domain.render;

/** Trạng thái {@code docgen.render_job.status} (TL-03). */
public enum RenderJobStatus {
  CHO,
  DANG_SINH,
  XONG,
  LOI;

  public boolean isTerminal() {
    return this == XONG || this == LOI;
  }
}
