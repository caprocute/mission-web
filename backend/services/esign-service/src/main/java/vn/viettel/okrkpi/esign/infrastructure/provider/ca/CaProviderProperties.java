package vn.viettel.okrkpi.esign.infrastructure.provider.ca;

import java.time.Duration;
import java.util.List;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.MySignProperties;

/**
 * Cấu hình một nhà cung cấp chứng thư ký từ xa theo CSC API (DV-10-04): {@code
 * okrkpi.esign.viettel-ca} hoặc {@code okrkpi.esign.gov-ca}. Địa chỉ dịch vụ, chứng thư gốc, OCSP
 * và CRL đều là cấu hình; đổi từ giả lập sang môi trường thật chỉ đổi các giá trị này. Bí mật
 * client và khoá kho đối tượng nạp từ OpenBao qua biến môi trường.
 *
 * @param mode {@code REMOTE} ký từ xa qua CSC API; {@code PKCS11} ký bằng token hoặc HSM tại đơn vị
 *     (chỉ CA chuyên dùng Chính phủ, nối bộ ký PKCS#11 của DV-10-03)
 * @param trustAnchors chứng thư gốc và CA trung gian tin cậy: đường dẫn tệp PEM hoặc nội dung PEM
 * @param ocspUrl máy chủ OCSP của CA; trống thì chỉ dùng CRL
 * @param crlUrl địa chỉ tải CRL của CA, dùng khi OCSP không trả lời; trống thì bỏ qua
 */
public record CaProviderProperties(
    boolean enabled,
    Mode mode,
    String baseUrl,
    String tokenUrl,
    String clientId,
    String clientSecret,
    String scope,
    MySignProperties.Paths paths,
    String tsaUrl,
    MySignProperties.Storage storage,
    Integer signatureSize,
    Duration tokenSkew,
    List<String> trustAnchors,
    String ocspUrl,
    String crlUrl) {

  /** Cách ký của nhà cung cấp. */
  public enum Mode {
    REMOTE,
    PKCS11
  }

  public CaProviderProperties {
    mode = mode == null ? Mode.REMOTE : mode;
    paths = paths == null ? new MySignProperties.Paths(null, null, null, null) : paths;
    storage =
        storage == null
            ? new MySignProperties.Storage(null, null, null, null, null, null)
            : storage;
    signatureSize = signatureSize == null ? 32_768 : signatureSize;
    tokenSkew = tokenSkew == null ? Duration.ofSeconds(30) : tokenSkew;
    trustAnchors = trustAnchors == null ? List.of() : List.copyOf(trustAnchors);
  }
}
