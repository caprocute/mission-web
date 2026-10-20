package vn.viettel.okrkpi.shared.platformtest;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.observability.TenantIdProvider;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/** Ứng dụng thử của NEN-016: ghi nhật ký trong yêu cầu, ném lỗi phụ thuộc. */
@SpringBootApplication
public class PlatformTestApplication {

  /** Tenant cố định thay cho ngữ cảnh tenant của NEN-010. */
  public static final TenantId TENANT =
      TenantId.of(UUID.fromString("0192f5a0-7b1c-7cde-8f00-0123456789ab"));

  @Bean
  TenantIdProvider testTenantIdProvider() {
    return request -> Optional.of(TENANT);
  }

  @RestController
  static class ProbeController {

    private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

    /** Ghi dòng nhật ký có JWT, số CCCD, email để kiểm tra che dữ liệu. */
    @GetMapping("/log-probe")
    String logProbe() {
      log.info(
          "Gọi thử token=eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl cccd=001203004567"
              + " email=nguyen.van.a@example.vn");
      return "ok";
    }

    @GetMapping("/dependency-down")
    void dependencyDown() {
      throw new DependencyUnavailableException(
          "vneid",
          DependencyUnavailableException.Reason.CIRCUIT_OPEN,
          Duration.ofSeconds(20),
          null);
    }

    @GetMapping("/circuit-open")
    void circuitOpen() {
      CircuitBreaker cb = CircuitBreaker.ofDefaults("probe");
      cb.transitionToOpenState();
      throw CallNotPermittedException.createCallNotPermittedException(cb);
    }

    @GetMapping("/bulkhead-full")
    void bulkheadFull() {
      throw BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("probe"));
    }
  }
}
