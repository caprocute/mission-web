package vn.viettel.okrkpi.evidence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SVC-08 evidence-service (DV-08-01 · TL-01 "SVC-08 evidence-service", ADR-13): siêu dữ liệu tệp ở
 * schema {@code evidence}, nội dung trên Ceph RGW qua S3 có Object Lock.
 *
 * <p>Cấu trúc gói lục giác như mẫu NEN-019: {@code domain}, {@code application}, {@code
 * infrastructure}.
 */
@SpringBootApplication
public class EvidenceServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(EvidenceServiceApplication.class, args);
  }
}
