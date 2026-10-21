package vn.viettel.okrkpi.replica.domain;

import java.util.UUID;

/** Đổi cấp trên tạo vòng trên cây tổ chức; sự kiện không bao giờ áp dụng được (vào DLQ). */
public class OrgTreeCycleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrgTreeCycleException(String tree, UUID unitId, UUID parentId) {
    super(
        "Đổi cấp trên tạo vòng trên cây "
            + tree
            + ": đơn vị "
            + unitId
            + " không thể trực thuộc hậu duệ "
            + parentId);
  }
}
