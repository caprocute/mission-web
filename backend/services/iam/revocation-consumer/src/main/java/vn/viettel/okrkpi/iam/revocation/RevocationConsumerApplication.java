package vn.viettel.okrkpi.iam.revocation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** DV-02-05 · Tiến trình ghi danh sách phiên bị thu hồi (một Deployment nhỏ trong namespace iam của cell). */
@SpringBootApplication
public class RevocationConsumerApplication {

  public static void main(String[] args) {
    SpringApplication.run(RevocationConsumerApplication.class, args);
  }
}
