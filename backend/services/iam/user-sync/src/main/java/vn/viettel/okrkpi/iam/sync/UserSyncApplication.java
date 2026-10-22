package vn.viettel.okrkpi.iam.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** DV-02-06 · Consumer EVT-16 đồng bộ người dùng sang Keycloak của cell. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UserSyncApplication {

  public static void main(String[] args) {
    SpringApplication.run(UserSyncApplication.class, args);
  }
}
