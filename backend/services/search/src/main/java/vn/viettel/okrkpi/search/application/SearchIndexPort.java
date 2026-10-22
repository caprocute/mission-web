package vn.viettel.okrkpi.search.application;

import java.util.List;
import vn.viettel.okrkpi.search.domain.IndexCommand;
import vn.viettel.okrkpi.search.domain.TenancyModel;

/** Cổng ghi chỉ mục tìm kiếm (hiện thực: OpenSearch). */
public interface SearchIndexPort {

  /**
   * Tạo bí danh {@code t_<tenant>_task} (và chỉ mục riêng khi không phải pool) nếu chưa có; gọi lặp
   * an toàn.
   */
  void ensureTenant(String tenantId, TenancyModel model);

  /**
   * Ghi lô thao tác qua bí danh của từng tenant.
   *
   * @return thao tác lỗi vĩnh viễn; thao tác bị bỏ vì phiên bản cũ hơn không tính là lỗi
   * @throws TransientIndexException khi cụm tạm thời không ghi được
   */
  List<IndexFailure> write(List<IndexCommand> commands);

  /** Tạo chỉ mục phiên bản kế tiếp cho phạm vi dựng lại, chưa đổi bí danh. */
  RebuildTarget startRebuild(RebuildScope scope);

  /** Ghi thẳng vào chỉ mục mới của lần dựng lại (có định tuyến theo tenant ở pool). */
  List<IndexFailure> write(RebuildTarget target, List<IndexCommand> commands);

  /**
   * Hoán mọi bí danh của phạm vi sang chỉ mục mới trong một lệnh {@code _aliases} nguyên tử.
   *
   * @return tenant pool được tiếp nhận trong lúc nạp (bí danh trỏ chỉ mục cũ nhưng không có trong
   *     {@code target}); bí danh của chúng cũng được hoán, dữ liệu của chúng cần phát lại từ đầu
   */
  java.util.Set<String> swap(RebuildTarget target);
}
