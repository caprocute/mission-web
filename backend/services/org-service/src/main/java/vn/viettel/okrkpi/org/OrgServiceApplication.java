package vn.viettel.okrkpi.org;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SVC-04 org-service: nguồn sự thật về cây tổ chức, nhân sự, phân công hai trục thời gian (TL-01, TL-03). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrgServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrgServiceApplication.class, args);
  }
}
