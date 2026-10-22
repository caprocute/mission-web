package vn.viettel.okrkpi.appraisal.application.selfassessment.port;

import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mã hoá trường C2 theo phong bì {@code FieldEncryptor} (TL-03); AAD gồm tenant, {@code
 * schema.bảng.cột} và mã dòng nên bản mã không chép được sang dòng khác.
 */
public interface TextSealer {

  /** {@code null} khi {@code plain} rỗng. */
  SealedText seal(TenantId tenant, String column, UUID rowId, String plain);

  /** {@code null} khi {@code sealed} rỗng. */
  String open(TenantId tenant, String column, UUID rowId, SealedText sealed);
}
