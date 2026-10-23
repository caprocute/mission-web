package vn.viettel.okrkpi.esign.application.sign;

import java.time.Instant;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;

/** Ghi EVT-18 {@code esign.signature.completed.v1} vào outbox trong cùng giao dịch. */
public interface SignatureEvents {

  void completed(SignRequest request, CertificateRef certificate);

  /**
   * EVT-18 cho một tài liệu của lô vừa ký xong (DV-10-06): mỗi phiếu một sự kiện, ghi qua outbox
   * trong cùng giao dịch với chữ ký.
   */
  default void itemSigned(
      SignRequestRepository.BatchHeader request,
      SignItem item,
      CertificateRef certificate,
      Instant signedAt) {
    throw new UnsupportedOperationException("Chưa ghi được EVT-18 cho tài liệu của lô");
  }
}
