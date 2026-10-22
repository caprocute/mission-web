package vn.viettel.okrkpi.config.application.port;

import java.util.List;

/**
 * Vô hiệu bộ đệm CDN cho các đường dẫn khi UI Pack mới phát hành (consumer EVT-23, CORE-08-11).
 * Giao diện CDN thật thuộc nhóm hạ tầng; trước khi có, bản mặc định chỉ ghi nhật ký.
 */
public interface CdnInvalidator {

  void invalidate(List<String> paths);
}
