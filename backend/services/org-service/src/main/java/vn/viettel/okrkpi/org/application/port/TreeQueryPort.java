package vn.viettel.okrkpi.org.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.domain.TreeKind;

/** Truy vấn cây bằng CTE đệ quy (TL-03 "Mẫu truy vấn"). */
public interface TreeQueryPort {

  /** Gốc của cây (độ sâu 0) và đơn vị cấp dưới tới {@code depth} tầng. */
  List<TreeRow> forest(TreeKind tree, TemporalQuery query, int depth);

  /** Đơn vị cấp dưới của {@code root} tới {@code depth} tầng (không gồm gốc). */
  List<TreeRow> subtree(TreeKind tree, UUID root, TemporalQuery query, int depth);

  /** Tổ tiên từ gốc tới cha trực tiếp. */
  List<TreeRow> ancestors(TreeKind tree, UUID unit, TemporalQuery query);

  /**
   * Một dòng cây. {@code hasMore} đúng khi nút ở tầng sâu nhất còn con chưa trả về.
   */
  record TreeRow(UUID id, UUID parentId, int depth, String code, String name, String unitType, boolean hasMore) {
  }
}
