package vn.viettel.okrkpi.org.domain;

/**
 * Vi phạm quy tắc nghiệp vụ, trả 422 Problem Details. {@code code} dạng {@code ORG-422-<TÊN>}, {@code msg} là mã
 * thông điệp TL-02 nếu có, {@code rule} là mã BR.
 */
public class OrgRuleViolation extends RuntimeException {

  private final String code;
  private final String msg;
  private final String rule;
  private final String field;

  public OrgRuleViolation(String code, String rule, String msg, String field, String detail) {
    super(detail);
    this.code = code;
    this.rule = rule;
    this.msg = msg;
    this.field = field;
  }

  public String code() {
    return code;
  }

  public String msg() {
    return msg;
  }

  public String rule() {
    return rule;
  }

  public String field() {
    return field;
  }
}
