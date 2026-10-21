package vn.viettel.okrkpi.org.domain.sync;

/** Trường lệch ({@code org.sync_discrepancy.field}), sáu loại của TL-02a SCR-0104 trường 4; mã khoá là suy luận. */
public enum DiscrepancyField {
  JOB_TITLE("job_title_id"),
  ORG_UNIT("org_unit_id"),
  PERSON_MATCH("person_match"),
  PARTY_MEMBERSHIP("party_membership"),
  EMPLOYMENT_STATUS("employment_status"),
  CONCURRENT_ASSIGNMENT("concurrent_assignment");

  private final String code;

  DiscrepancyField(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static DiscrepancyField ofCode(String code) {
    for (DiscrepancyField f : values()) {
      if (f.code.equals(code)) {
        return f;
      }
    }
    throw new IllegalArgumentException(code);
  }
}
