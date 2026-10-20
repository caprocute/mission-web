package vn.viettel.okrkpi.shared.tenant;

/** Thao tác cần ngữ cảnh tenant nhưng luồng hiện hành không có. */
public class MissingTenantException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public MissingTenantException() {
    super("Thiếu ngữ cảnh tenant cho thao tác cần tenant");
  }
}
