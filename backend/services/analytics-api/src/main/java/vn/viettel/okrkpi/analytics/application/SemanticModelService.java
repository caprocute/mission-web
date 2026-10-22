package vn.viettel.okrkpi.analytics.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;

/**
 * Lớp ngữ nghĩa hiện hành, đọc từ siêu dữ liệu Cube và giữ trong bộ nhớ một khoảng ngắn. Mô hình
 * Cube giống nhau giữa các tenant của cell (model/ trong repo), nên một bản dùng chung là đủ.
 */
public class SemanticModelService {

  private record Entry(SemanticCatalog catalog, Instant loadedAt) {}

  private final CubePort cube;
  private final Duration refresh;
  private final Clock clock;
  private volatile Entry entry;

  public SemanticModelService(CubePort cube, Duration refresh, Clock clock) {
    this.cube = cube;
    this.refresh = refresh;
    this.clock = clock;
  }

  public SemanticCatalog current(Scope scope) {
    Entry e = entry;
    Instant now = clock.instant();
    if (e == null || !now.isBefore(e.loadedAt().plus(refresh))) {
      e = new Entry(cube.model(scope), now);
      entry = e;
    }
    return e.catalog();
  }
}
