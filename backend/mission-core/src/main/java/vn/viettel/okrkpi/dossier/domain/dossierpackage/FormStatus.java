package vn.viettel.okrkpi.dossier.domain.dossierpackage;

/** Trạng thái biểu mẫu ({@code dossier.form_instance.status}). */
public enum FormStatus {
  CHO_SINH,
  DA_SINH,
  DA_KY,
  LOI;

  /** Tệp đã có trên kho đối tượng (EVT-17 đã đến). */
  public boolean rendered() {
    return this == DA_SINH || this == DA_KY;
  }
}
