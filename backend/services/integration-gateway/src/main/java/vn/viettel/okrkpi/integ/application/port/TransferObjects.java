package vn.viettel.okrkpi.integ.application.port;

/**
 * Kho đối tượng của gói chuyển liên cell (bucket {@code transfer} trên Ceph RGW, khoá giữ
 * GOVERNANCE 1 năm): chỉ chứa bản mã.
 */
public interface TransferObjects {

  void put(String key, byte[] content);

  byte[] get(String key);
}
