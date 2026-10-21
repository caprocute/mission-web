package vn.viettel.okrkpi.workflow;

/** Hàm giả lập {@code set_config} của PostgreSQL trên H2 phục vụ kiểm thử đơn vị nhanh. */
public final class H2Functions {

  private H2Functions() {}

  public static String setConfig(String name, String value, boolean isLocal) {
    return value;
  }
}
