package vn.viettel.okrkpi.search.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import vn.viettel.okrkpi.search.domain.TenancyModel;

/**
 * Cấu hình SVC-16 ({@code mission.search.*}).
 *
 * @param opensearchUrl địa chỉ cụm OpenSearch của cell
 * @param opensearchUsername tài khoản kỹ thuật của indexer (mật khẩu chỉ qua biến môi trường)
 * @param defaultModel mô hình thuê bao của cell, dùng khi sự kiện của tenant đến trước {@code
 *     sys.tenant.provisioned.v1}
 * @param payloadFormat {@code apicurio} (mặc định, đọc lược đồ ghi từ Apicurio Registry) hoặc
 *     {@code avro-binary} (thân Avro nhị phân cùng phiên bản lược đồ, cho môi trường không có
 *     registry)
 * @param shards số shard chính của chỉ mục mới (TL-04 định cỡ OpenSearch)
 * @param cursorKey khoá AES-256 (Base64) mã hoá con trỏ trang, cấp từ OpenBao
 * @param rebuild {@code pool} hoặc mã tenant: chạy lệnh dựng lại chỉ mục rồi thoát (Job vận hành)
 */
@ConfigurationProperties("mission.search")
public record SearchProperties(
    String opensearchUrl,
    String opensearchUsername,
    String opensearchPassword,
    Duration timeout,
    TenancyModel defaultModel,
    String payloadFormat,
    String registryUrl,
    int shards,
    int replicas,
    int concurrency,
    String consumerGroup,
    String cursorKey,
    String rebuild) {

  public SearchProperties {
    opensearchUrl = opensearchUrl == null ? "http://127.0.0.1:35200" : opensearchUrl;
    timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    defaultModel = defaultModel == null ? TenancyModel.POOL : defaultModel;
    payloadFormat = payloadFormat == null ? "apicurio" : payloadFormat;
    shards = shards <= 0 ? 1 : shards;
    replicas = Math.max(replicas, 0);
    concurrency = concurrency <= 0 ? 3 : concurrency;
    consumerGroup =
        consumerGroup == null || consumerGroup.isBlank() ? "search-indexer" : consumerGroup;
  }
}
