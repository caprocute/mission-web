package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import java.util.Optional;

/** Kho đối tượng của esign: đọc PDF cần ký, ghi tệp trung gian của phiên và tệp đã ký. */
public interface ObjectStorage {

  Optional<byte[]> get(String key);

  void put(String key, byte[] content, String contentType);
}
