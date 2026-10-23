package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.util.UUID;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.service.ServiceSupport;
import vn.viettel.okrkpi.integ.application.InboundPipeline;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho khoá của {@code idempotentConsumer} trỏ tới {@code integ.inbox_message} (TL-01 "Lớp chống ô
 * nhiễm"): khoá {@code <tenant>|<mã kết nối>|<mã thông điệp ngoài>} là trùng khi thông điệp đã xử
 * lý xong trong cửa sổ chống trùng. Dòng do {@link InboundPipeline} ghi, nên kho không ghi gì và
 * không cần bảng {@code CAMEL_MESSAGEPROCESSED} ngoài TL-03.
 */
public class InboxIdempotentRepository extends ServiceSupport implements IdempotentRepository {

  private final InboundPipeline pipeline;

  public InboxIdempotentRepository(InboundPipeline pipeline) {
    this.pipeline = pipeline;
  }

  /** Khoá của thông điệp. */
  public static String key(TenantId tenant, String connectorCode, String externalMsgId) {
    return tenant.value() + "|" + connectorCode + "|" + externalMsgId;
  }

  @Override
  public boolean add(String key) {
    return !contains(key);
  }

  @Override
  public boolean contains(String key) {
    String[] p = key.split("\\|", 3);
    if (p.length != 3) {
      throw new IllegalArgumentException("Khoá chống trùng không hợp lệ");
    }
    return pipeline.processed(TenantId.of(UUID.fromString(p[0])), p[1], p[2]);
  }

  @Override
  public boolean remove(String key) {
    return true;
  }

  @Override
  public boolean confirm(String key) {
    return true;
  }

  @Override
  public void clear() {
    // Dòng inbox_message là nguồn sự thật; không xoá.
  }
}
