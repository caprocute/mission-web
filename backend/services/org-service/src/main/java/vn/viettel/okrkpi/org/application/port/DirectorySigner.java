package vn.viettel.okrkpi.org.application.port;

import java.util.List;
import java.util.UUID;

/**
 * Khoá ký danh bạ khoá công khai của tenant (ADR-27): ECDSA P-256 trong OpenBao Transit, khoá riêng không rời
 * OpenBao. Không ký được thì ném {@link IntegrationUnavailableException} (503).
 */
public interface DirectorySigner {

  Signed sign(UUID tenantId, byte[] payload);

  /** Các phiên bản khoá công khai để thiết bị kiểm chữ ký. */
  List<VerificationKey> verificationKeys(UUID tenantId);

  /** Chữ ký DER và phiên bản khoá ký. */
  record Signed(int keyVersion, byte[] signature) {
  }

  record VerificationKey(int keyVersion, String publicKeyPem) {
  }
}
