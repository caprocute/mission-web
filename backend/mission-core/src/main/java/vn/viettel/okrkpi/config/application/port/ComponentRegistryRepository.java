package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import vn.viettel.okrkpi.config.domain.UiComponent;

/** Kho {@code config.component_registry}, danh mục dùng chung của nền tảng (CORE-08-09). */
public interface ComponentRegistryRepository {

  /** Các phiên bản registry đã nạp, không theo thứ tự. */
  List<String> versions();

  /**
   * Component của một phiên bản theo khoá tăng dần, sau {@code afterKey} nếu có.
   *
   * @param status {@code null} là mọi trạng thái
   */
  List<UiComponent> list(String version, UiComponent.Status status, String afterKey, int limit);

  /** Toàn bộ component của một phiên bản. */
  List<UiComponent> all(String version);

  /** Nạp một phiên bản mới; cùng giao dịch, gặp phiên bản đã có thì không ghi gì. */
  void insertVersion(List<UiComponent> components);
}
