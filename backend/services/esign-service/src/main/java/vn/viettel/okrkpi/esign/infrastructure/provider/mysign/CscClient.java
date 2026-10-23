package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Client CSC API v2 của MySign (TL-01 "Các bộ kết nối", dòng MySign/CSC API): token OAuth2 client
 * credentials, {@code credentials/list}, {@code credentials/info}, {@code signatures/signHash} ở
 * chế độ bất đồng bộ ({@code operationMode = A}) và thăm dò kết quả theo {@code responseID}. Mọi
 * lời gọi đi qua instance Resilience4j {@code esign-mysign} (bulkhead riêng, ngắt mạch, giới hạn
 * thời gian).
 *
 * <p>Thăm dò: CSC v2 trả {@code responseID} cho chế độ bất đồng bộ; thân lỗi của lúc chưa xác nhận,
 * từ chối, hết hạn theo kiểu OAuth2 ({@code authorization_pending}, {@code access_denied}, {@code
 * expired_token}) là suy luận (wiki trang 14), đường dẫn đặt trong cấu hình để khớp MySign thật.
 */
public class CscClient {

  static final String PENDING = "authorization_pending";
  static final String DENIED = "access_denied";
  static final String EXPIRED = "expired_token";

  private final ResilientClient api;
  private final ResilientClient tokenEndpoint;
  private final Connection properties;
  private final Clock clock;
  private volatile Token token;

  /**
   * Tham số kết nối CSC của một nhà cung cấp (MySign, Viettel-CA, CA chuyên dùng Chính phủ): client
   * OAuth2 và đường dẫn các thao tác.
   */
  public record Connection(
      String clientId,
      String clientSecret,
      String scope,
      MySignProperties.Paths paths,
      java.time.Duration tokenSkew) {
    public Connection {
      paths = paths == null ? new MySignProperties.Paths(null, null, null, null) : paths;
      tokenSkew = tokenSkew == null ? java.time.Duration.ofSeconds(30) : tokenSkew;
    }
  }

  public CscClient(
      ResilientClient api,
      ResilientClient tokenEndpoint,
      MySignProperties properties,
      Clock clock) {
    this(
        api,
        tokenEndpoint,
        new Connection(
            properties.clientId(),
            properties.clientSecret(),
            properties.scope(),
            properties.paths(),
            properties.tokenSkew()),
        clock);
  }

  public CscClient(
      ResilientClient api, ResilientClient tokenEndpoint, Connection connection, Clock clock) {
    this.api = Objects.requireNonNull(api, "api");
    this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
    this.properties = Objects.requireNonNull(connection, "connection");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Mạch tới MySign chưa ngắt (đọc trạng thái cục bộ, không gọi ra ngoài). */
  public boolean available() {
    return api.circuitBreaker().getState()
        != io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
  }

  /** Mã chứng thư ký (credential) của người dùng ở MySign. */
  public List<String> credentials(String userId) {
    CredentialList body =
        call(
            properties.paths().credentialsList(),
            Map.of("userID", userId, "maxResults", 20),
            CredentialList.class);
    return body == null || body.credentialIDs() == null ? List.of() : body.credentialIDs();
  }

  /** Thông tin khoá và chuỗi chứng thư của một credential. */
  public CredentialInfo info(String credentialId) {
    CredentialInfo body =
        call(
            properties.paths().credentialsInfo(),
            Map.of("credentialID", credentialId, "certificates", "chain", "certInfo", true),
            CredentialInfo.class);
    if (body == null || body.cert() == null || body.cert().certificates() == null) {
      throw new SignatureProviderException("MySign không trả chứng thư của người ký", true);
    }
    return body;
  }

  /** Gửi các băm cần ký, người ký xác nhận trên ứng dụng MySign; trả {@code responseID}. */
  public String signHash(String credentialId, List<byte[]> hashes, String signAlgo) {
    Map<String, Object> request =
        Map.of(
            "credentialID",
            credentialId,
            "hashes",
            hashes.stream().map(h -> Base64.getEncoder().encodeToString(h)).toList(),
            "hashAlgorithmOID",
            Cms.SHA256_OID,
            "signAlgo",
            signAlgo,
            "operationMode",
            "A");
    SignHashResponse body = call(properties.paths().signHash(), request, SignHashResponse.class);
    if (body == null || body.responseID() == null || body.responseID().isBlank()) {
      throw new SignatureProviderException("MySign không trả mã phiên ký bất đồng bộ", true);
    }
    return body.responseID();
  }

  /**
   * Ký đồng bộ ({@code operationMode} S) bằng credential của tổ chức, không cần người xác nhận
   * (DV-13-10, dấu số tổ chức); trả chữ ký thô theo thứ tự băm đã gửi.
   */
  public List<byte[]> signHashNow(String credentialId, List<byte[]> hashes, String signAlgo) {
    Map<String, Object> request =
        Map.of(
            "credentialID",
            credentialId,
            "hashes",
            hashes.stream().map(h -> Base64.getEncoder().encodeToString(h)).toList(),
            "hashAlgorithmOID",
            Cms.SHA256_OID,
            "signAlgo",
            signAlgo,
            "operationMode",
            "S");
    SignHashResponse body = call(properties.paths().signHash(), request, SignHashResponse.class);
    if (body == null || body.signatures() == null || body.signatures().size() != hashes.size()) {
      throw new SignatureProviderException("MySign không trả đủ chữ ký tổ chức", true);
    }
    return body.signatures().stream().map(x -> Base64.getDecoder().decode(x)).toList();
  }

  /** Kết quả thăm dò một phiên ký bất đồng bộ. */
  public Poll poll(String responseId) {
    try {
      SignHashResponse body =
          call(
              properties.paths().signPolling(),
              Map.of("responseID", responseId),
              SignHashResponse.class);
      if (body == null || body.signatures() == null || body.signatures().isEmpty()) {
        return new Poll(Poll.State.PENDING, List.of());
      }
      return new Poll(
          Poll.State.SIGNED,
          body.signatures().stream().map(s -> Base64.getDecoder().decode(s)).toList());
    } catch (CscError e) {
      return switch (e.code) {
        case PENDING -> new Poll(Poll.State.PENDING, List.of());
        case DENIED -> new Poll(Poll.State.REJECTED, List.of());
        case EXPIRED -> new Poll(Poll.State.EXPIRED, List.of());
        default -> throw e;
      };
    }
  }

  /**
   * Mở phiên ủy quyền ký lô ({@code credentials/authorize}, bất đồng bộ): người ký xác nhận một lần
   * trên ứng dụng cho {@code numSignatures} chữ ký; trả {@code responseID} (DV-10-06, suy luận theo
   * CSC API v2).
   */
  public String authorize(String credentialId, int numSignatures) {
    SignHashResponse body =
        call(
            properties.paths().authorize(),
            Map.of(
                "credentialID", credentialId, "numSignatures", numSignatures, "operationMode", "A"),
            SignHashResponse.class);
    if (body == null || body.responseID() == null || body.responseID().isBlank()) {
      throw new SignatureProviderException("MySign không trả mã phiên ủy quyền ký lô", true);
    }
    return body.responseID();
  }

  /** Kết quả phiên ủy quyền ký lô: khi đã xác nhận thì có SAD và hạn dùng. */
  public Authorization authorizeCheck(String responseId) {
    try {
      AuthorizeResponse body =
          call(
              properties.paths().authorizeCheck(),
              Map.of("responseID", responseId),
              AuthorizeResponse.class);
      if (body == null || body.sad() == null || body.sad().isBlank()) {
        return new Authorization(Poll.State.PENDING, null, null);
      }
      Instant until =
          body.expiresIn() == null ? null : clock.instant().plusSeconds(body.expiresIn());
      return new Authorization(Poll.State.SIGNED, body.sad(), until);
    } catch (CscError e) {
      return switch (e.code) {
        case PENDING -> new Authorization(Poll.State.PENDING, null, null);
        case DENIED -> new Authorization(Poll.State.REJECTED, null, null);
        case EXPIRED -> new Authorization(Poll.State.EXPIRED, null, null);
        default -> throw e;
      };
    }
  }

  /** Ký đồng bộ các băm bằng SAD của phiên ủy quyền ký lô; trả chữ ký theo thứ tự băm. */
  public List<byte[]> signHashWithSad(
      String credentialId, String sad, List<byte[]> hashes, String signAlgo) {
    Map<String, Object> request =
        Map.of(
            "credentialID",
            credentialId,
            "SAD",
            sad,
            "hashes",
            hashes.stream().map(h -> Base64.getEncoder().encodeToString(h)).toList(),
            "hashAlgorithmOID",
            Cms.SHA256_OID,
            "signAlgo",
            signAlgo,
            "operationMode",
            "S");
    SignHashResponse body = call(properties.paths().signHash(), request, SignHashResponse.class);
    if (body == null || body.signatures() == null || body.signatures().size() != hashes.size()) {
      throw new SignatureProviderException("MySign trả thiếu chữ ký cho lô", false);
    }
    return body.signatures().stream().map(s -> Base64.getDecoder().decode(s)).toList();
  }

  private <T> T call(String path, Object body, Class<T> type) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(accessToken());
    try {
      return api.exchange(HttpMethod.POST, path, body, headers, type);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 401) {
        token = null;
      }
      throw CscError.of(e);
    }
  }

  private String accessToken() {
    Token current = token;
    Instant now = clock.instant();
    if (current != null && now.isBefore(current.refreshAt())) {
      return current.value();
    }
    synchronized (this) {
      current = token;
      if (current != null && now.isBefore(current.refreshAt())) {
        return current.value();
      }
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", "client_credentials");
      form.add("client_id", properties.clientId());
      form.add("client_secret", properties.clientSecret());
      if (properties.scope() != null && !properties.scope().isBlank()) {
        form.add("scope", properties.scope());
      }
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      TokenResponse response;
      try {
        response = tokenEndpoint.exchange(HttpMethod.POST, "", form, headers, TokenResponse.class);
      } catch (HttpClientErrorException e) {
        throw new SignatureProviderException(
            "MySign từ chối xác thực client (" + e.getStatusCode().value() + ")", true, e);
      }
      if (response == null || response.access_token() == null) {
        throw new SignatureProviderException("MySign không cấp token", false);
      }
      long ttl = response.expires_in() == null ? 300 : response.expires_in();
      Duration life = Duration.ofSeconds(ttl).minus(properties.tokenSkew());
      token =
          new Token(response.access_token(), now.plus(life.isNegative() ? Duration.ZERO : life));
      return token.value();
    }
  }

  /** Lỗi 4xx của CSC: mã lỗi nghiệp vụ, mặc định là lỗi vĩnh viễn. */
  static final class CscError extends SignatureProviderException {
    final String code;

    CscError(String code, String message, Throwable cause) {
      super(message, true, cause);
      this.code = code;
    }

    static CscError of(HttpClientErrorException e) {
      ErrorBody body = null;
      try {
        body = e.getResponseBodyAs(ErrorBody.class);
      } catch (RuntimeException ignored) {
        // Thân không phải JSON: chỉ còn mã HTTP.
      }
      String code =
          body == null || body.error() == null ? "http_" + e.getStatusCode().value() : body.error();
      return new CscError(code, "MySign trả lỗi " + code, e);
    }
  }

  private record Token(String value, Instant refreshAt) {}

  record TokenResponse(String access_token, Long expires_in) {}

  record ErrorBody(String error, String error_description) {}

  record CredentialList(List<String> credentialIDs) {}

  record SignHashResponse(List<String> signatures, String responseID) {}

  record AuthorizeResponse(
      @com.fasterxml.jackson.annotation.JsonProperty("SAD") String sad, Long expiresIn) {}

  /** Phiên ủy quyền ký lô: trạng thái, SAD khi đã xác nhận, hạn dùng SAD. */
  public record Authorization(Poll.State state, String sad, Instant expiresAt) {}

  /** Thân {@code credentials/info}: khoá và chứng thư (chuỗi base64 DER, phần tử đầu là lá). */
  public record CredentialInfo(Key key, Cert cert) {
    /** Khoá: {@code status} enabled/disabled, {@code algo} các OID. */
    public record Key(String status, List<String> algo, Integer len) {}

    /** Chứng thư: {@code status} valid/expired/revoked/suspended. */
    public record Cert(String status, List<String> certificates) {}
  }

  /** Trạng thái phiên và chữ ký thô (theo thứ tự băm đã gửi) khi đã ký. */
  public record Poll(State state, List<byte[]> signatures) {
    /** Trạng thái phiên ký. */
    public enum State {
      PENDING,
      SIGNED,
      REJECTED,
      EXPIRED
    }
  }
}
