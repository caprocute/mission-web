package vn.viettel.okrkpi.integ.application;

/** Mã ngoài đang gắn với mã nội bộ khác mà yêu cầu không cho phép thay (409). */
public class MappingConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MappingConflictException(String message) {
    super(message);
  }
}
