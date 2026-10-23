package vn.viettel.okrkpi.esign.infrastructure.tsa;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Máy chủ dấu thời gian RFC 3161 (DV-10-03).
 *
 * @param enabled bật client dấu thời gian dùng chung
 * @param urls địa chỉ TSA theo thứ tự ưu tiên; TSA sau chỉ được gọi khi TSA trước lỗi
 * @param trustAnchors tệp PEM chứng thư gốc (hoặc chứng thư TSA) được tin cậy
 */
@ConfigurationProperties(prefix = "okrkpi.esign.tsa")
public record TsaProperties(boolean enabled, List<String> urls, List<String> trustAnchors) {
  public TsaProperties {
    urls = urls == null ? List.of() : List.copyOf(urls);
    trustAnchors = trustAnchors == null ? List.of() : List.copyOf(trustAnchors);
  }
}
