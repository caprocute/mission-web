package vn.viettel.okrkpi.esign.domain.sign;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kho chứng thư ({@code esign.certificate_ref}). */
public interface CertificateRepository {

  Optional<CertificateRef> findById(UUID id);

  List<CertificateRef> findByPerson(UUID personId);

  /** Thêm hoặc lấy lại chứng thư theo (tenant, dấu vân tay); trả bản ghi đang lưu. */
  CertificateRef upsert(CertificateRef certificate);
}
