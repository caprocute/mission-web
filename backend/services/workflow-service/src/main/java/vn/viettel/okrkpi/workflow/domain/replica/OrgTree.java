package vn.viettel.okrkpi.workflow.domain.replica;

/**
 * Hai cây tổ chức của bản sao đơn vị (TL-03 {@code org.org_unit_relation}): cây Đảng theo {@code
 * party_parent_id} và cây chuyên môn theo {@code admin_parent_id}.
 */
public enum OrgTree {
  /** Cây Đảng. */
  DANG,
  /** Cây chuyên môn. */
  CHUYEN_MON
}
