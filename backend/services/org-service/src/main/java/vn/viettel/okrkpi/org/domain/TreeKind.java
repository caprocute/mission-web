package vn.viettel.okrkpi.org.domain;

/** Cây tổ chức song song ({@code org.org_unit_relation.tree}). */
public enum TreeKind {
  DANG,
  CHUYEN_MON;

  /**
   * Cây mặc định của một đơn vị khi hợp đồng không nêu cây (suy luận): tổ chức đảng và chi bộ thuộc cây Đảng,
   * các loại còn lại thuộc cây chuyên môn.
   */
  public static TreeKind defaultFor(String unitType) {
    return "TO_CHUC_DANG".equals(unitType) || "CHI_BO".equals(unitType) ? DANG : CHUYEN_MON;
  }
}
