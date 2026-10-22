package vn.viettel.okrkpi.analytics.application;

import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;

/** Cổng tới Cube (lớp ngữ nghĩa, ADR-14): mọi lời gọi mang ngữ cảnh an ninh tenant và org_path. */
public interface CubePort {

  /** Siêu dữ liệu các cube công bố, quy về lớp ngữ nghĩa. */
  SemanticCatalog model(Scope scope);

  /**
   * Chạy một truy vấn Cube (đối tượng JSON của {@code /cubejs-api/v1/load}); trả các dòng, khoá là
   * tên thành viên ({@code cube.member}, chiều thời gian có thêm {@code .granularity}).
   */
  List<Map<String, Object>> load(Scope scope, Map<String, Object> query);
}
