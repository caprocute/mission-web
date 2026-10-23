package vn.viettel.okrkpi.integ.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mã hoá phong bì nội dung mức C2 ({@code raw_enc}, {@code payload_enc}; NEN-015): AAD gồm toạ độ
 * cột và mã dòng nên bản mã không chép được sang dòng khác.
 */
public interface PayloadCipher {

  record Sealed(byte[] ciphertext, UUID dekId) {}

  Sealed seal(TenantId tenant, String column, UUID rowId, byte[] plain);

  byte[] open(TenantId tenant, String column, UUID rowId, byte[] ciphertext);
}
