package vn.viettel.okrkpi.config.infrastructure.storage;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.config.application.port.CdnInvalidator;

/**
 * Bản mặc định khi chưa có giao diện vô hiệu của CDN: chỉ ghi nhật ký. Con trỏ {@code current.json}
 * có {@code max-age=60}, nên người dùng nhận bản mới chậm nhất sau một phút (TL-01 "Cơ chế bộ
 * đệm").
 */
public class LoggingCdnInvalidator implements CdnInvalidator {

  private static final Logger log = LoggerFactory.getLogger(LoggingCdnInvalidator.class);

  @Override
  public void invalidate(List<String> paths) {
    log.info("Vô hiệu CDN cho {}", paths);
  }
}
