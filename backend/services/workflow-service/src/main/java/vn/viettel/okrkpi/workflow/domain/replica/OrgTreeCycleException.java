package vn.viettel.okrkpi.workflow.domain.replica;

import java.util.UUID;

/**
 * Đổi cấp trên tạo vòng trên cây tổ chức: sự kiện không bao giờ áp được, nên consumer coi là lỗi
 * vĩnh viễn và chuyển vào thư chết (như {@code OrgTreeCycleException} của mission-core).
 */
public class OrgTreeCycleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OrgTree tree;

  public OrgTreeCycleException(OrgTree tree, UUID unitId, UUID parentId) {
    super(
        "Đổi cấp trên tạo vòng trên cây "
            + tree
            + ": đơn vị "
            + unitId
            + " không thể trực thuộc "
            + parentId);
    this.tree = tree;
  }

  /** Cây bị vòng. */
  public OrgTree tree() {
    return tree;
  }
}
