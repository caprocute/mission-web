package vn.viettel.okrkpi.integ.infrastructure.web;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.integ.application.port.PayloadSigner;

/**
 * Công bố khoá công khai ký thông điệp ra dạng JWKS (DV-13-02, suy luận về cách công bố): {@code
 * GET /.well-known/jwks.json} không cần xác thực, chỉ chứa phần công khai của khoá. Gateway đối tác
 * (APISIX) chuyển tiếp đường này; khi xoay khoá, khoá cũ còn trong tệp vẫn được công bố.
 */
@RestController
public class JwksController {

  public static final String PATH = "/.well-known/jwks.json";
  static final MediaType JWK_SET = MediaType.parseMediaType("application/jwk-set+json");

  private final PayloadSigner signer;

  public JwksController(PayloadSigner signer) {
    this.signer = signer;
  }

  @GetMapping(value = PATH, produces = "application/jwk-set+json")
  public ResponseEntity<String> jwks() {
    return ResponseEntity.ok()
        .contentType(JWK_SET)
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(signer.publicJwks());
  }
}
