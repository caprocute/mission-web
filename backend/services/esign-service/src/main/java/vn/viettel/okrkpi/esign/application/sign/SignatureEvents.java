package vn.viettel.okrkpi.esign.application.sign;

import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;

/** Ghi EVT-18 {@code esign.signature.completed.v1} vào outbox trong cùng giao dịch. */
public interface SignatureEvents {

  void completed(SignRequest request, CertificateRef certificate);
}
