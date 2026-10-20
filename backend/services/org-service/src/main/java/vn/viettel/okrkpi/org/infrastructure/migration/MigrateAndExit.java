package vn.viettel.okrkpi.org.infrastructure.migration;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Hồ sơ {@code migrate}: Flyway chạy bằng tài khoản {@code mig_org} khi khởi động (Kubernetes Job, Helm hook
 * pre-upgrade) rồi thoát; pod ứng dụng chạy {@code svc_org} với {@code spring.flyway.enabled=false} (TL-03 "Ứng dụng
 * không có quyền DDL"). Thay bằng {@code MigrationRunner} của NEN-030 khi có.
 */
@Component
@Profile("migrate")
public class MigrateAndExit implements CommandLineRunner {

  private final ApplicationContext context;

  public MigrateAndExit(ApplicationContext context) {
    this.context = context;
  }

  @Override
  public void run(String... args) {
    System.exit(SpringApplication.exit(context, () -> 0));
  }
}
