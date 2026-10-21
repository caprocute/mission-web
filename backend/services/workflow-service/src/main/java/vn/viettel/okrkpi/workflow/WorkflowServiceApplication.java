package vn.viettel.okrkpi.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Điểm khởi chạy mẫu microservice Java (NEN-019 · TL-04 WI-20, WI-23).
 *
 * <p>Cấu trúc gói lục giác: {@code api}, {@code application}, {@code domain}, {@code
 * infrastructure} và {@code internal}.
 */
@SpringBootApplication
public class WorkflowServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(WorkflowServiceApplication.class, args);
  }
}
