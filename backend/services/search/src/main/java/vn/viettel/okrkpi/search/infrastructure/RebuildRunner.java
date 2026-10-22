package vn.viettel.okrkpi.search.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.search.application.RebuildScope;
import vn.viettel.okrkpi.search.application.RebuildService;

/**
 * Lệnh dựng lại chỉ mục cho Job vận hành: chạy ảnh của service với {@code
 * MISSION_SEARCH_REBUILD=pool} (hoặc mã tenant) và {@code MISSION_SEARCH_LISTEN=false}; tiến trình
 * dựng lại, hoán bí danh rồi thoát với mã 0 khi thành công.
 */
@Component
@ConditionalOnProperty(prefix = "mission.search", name = "rebuild")
public class RebuildRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RebuildRunner.class);

  private final RebuildService rebuild;
  private final SearchProperties props;
  private final ConfigurableApplicationContext context;

  public RebuildRunner(
      RebuildService rebuild, SearchProperties props, ConfigurableApplicationContext context) {
    this.rebuild = rebuild;
    this.props = props;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    RebuildService.Report r = rebuild.rebuild(RebuildScope.parse(props.rebuild()));
    LOG.info(
        "Dựng lại chỉ mục xong: {} -> {}, {} sự kiện, {} lỗi",
        r.oldIndex(),
        r.newIndex(),
        r.replayed() + r.caughtUp(),
        r.failures());
    exit(SpringApplication.exit(context, () -> r.failures() == 0 ? 0 : 2));
  }

  /** Kết thúc tiến trình của Job; tách riêng để kiểm thử thay thế. */
  protected void exit(int code) {
    Runtime.getRuntime().halt(code);
  }
}
