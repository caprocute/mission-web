package vn.viettel.okrkpi.integ;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SVC-13 integration-gateway (DV-13-01 · ADR-20 · TL-01 "SVC-13 integration-gateway"): lớp chống ô
 * nhiễm giữa nền tảng và hệ ngoài, mỗi bộ kết nối là một route Camel có bulkhead, circuit breaker
 * và thư chết riêng.
 */
@SpringBootApplication
public class IntegrationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(IntegrationServiceApplication.class, args);
  }
}
