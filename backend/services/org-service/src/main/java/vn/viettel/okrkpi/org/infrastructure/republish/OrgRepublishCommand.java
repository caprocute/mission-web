package vn.viettel.okrkpi.org.infrastructure.republish;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.republish.OrgRepublishService;
import vn.viettel.okrkpi.org.application.republish.RepublishReport;
import vn.viettel.okrkpi.org.application.republish.RepublishRequest;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/**
 * DV-04-08 · Lệnh {@code org-republish} của ảnh org-service, chạy như Kubernetes Job (hồ sơ {@code republish}, không
 * mở endpoint):
 * <pre>
 * --tenant=&lt;uuid&gt; [--entity=unit|assignment|person ...] [--bucket=0..255 ...] [--as-of=ISO-8601]
 * [--dry-run] [--hashes-only] [--operator=&lt;mã tài khoản hoặc tên&gt;]
 * </pre>
 * {@code --entity}, {@code --bucket} lặp lại hoặc phân tách bằng dấu phẩy. Mã thoát 0 khi thành công, 2 khi tham số
 * sai, 1 khi lỗi chạy.
 */
@Component
@Profile("republish")
public class OrgRepublishCommand implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(OrgRepublishCommand.class);

  private final OrgRepublishService service;
  private final ObjectProvider<MeterRegistry> meters;
  private final ApplicationContext context;
  private final RepublishProperties props;

  public OrgRepublishCommand(OrgRepublishService service, ObjectProvider<MeterRegistry> meters,
      ApplicationContext context, RepublishProperties props) {
    this.service = service;
    this.meters = meters;
    this.context = context;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) {
    int code = execute(args);
    if (props.exitOnFinish()) {
      int exit = SpringApplication.exit(context, () -> code);
      System.exit(exit);
    }
  }

  int execute(ApplicationArguments args) {
    RepublishRequest req;
    try {
      req = OrgRepublishCommandArgs.parse(args);
    } catch (IllegalArgumentException e) {
      LOG.error("org-republish: tham số không hợp lệ: {}", e.getMessage());
      return 2;
    }
    TenantContextHolder.set(req.tenant());
    try {
      RepublishReport report = service.run(req);
      record(req, report);
      return 0;
    } catch (RuntimeException e) {
      LOG.error("org-republish thất bại cho tenant {}", req.tenant(), e);
      return 1;
    } finally {
      TenantContextHolder.clear();
    }
  }

  private void record(RepublishRequest req, RepublishReport report) {
    MeterRegistry registry = meters.getIfAvailable();
    if (registry == null) {
      return;
    }
    Timer.builder("org.republish.duration").tag("dry_run", Boolean.toString(req.dryRun()))
        .register(registry).record(report.duration());
    report.counts().forEach((kind, n) -> Counter.builder("org.republish.entities").tag("kind", kind)
        .register(registry).increment(n));
    double seconds = Math.max(report.duration().toMillis(), 1) / 1000.0;
    LOG.info("org-republish xong batch={} entities={} throughput={}/s", report.batchId(), report.entities(),
        Math.round(report.entities() / seconds));
  }
}
