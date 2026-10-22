package vn.viettel.okrkpi.esign.domain.sign;

/** Trạng thái {@code esign.sign_request.status} (TL-03). */
public enum SignRequestStatus {
  TAO,
  CHO_XAC_NHAN,
  DA_KY,
  TU_CHOI,
  HET_HAN,
  LOI;

  public boolean isTerminal() {
    return this == DA_KY || this == TU_CHOI || this == HET_HAN || this == LOI;
  }
}
