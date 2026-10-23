package vn.viettel.okrkpi.integ.infrastructure.anchor;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import vn.viettel.okrkpi.integ.application.anchor.AnchorSettings;
import vn.viettel.okrkpi.integ.application.anchor.AnchorTarget;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cấu hình luồng neo (DV-13-09, {@code okrkpi.integ.anchor}); không chứa bí mật, chỉ đường dẫn tệp
 * mã thông báo do OpenBao Agent gắn vào pod.
 *
 * @param enabled bật luồng neo
 * @param operatorTenant tenant vận hành cell giữ các kết nối neo
 * @param cell mã cell; trống thì dùng {@code okrkpi.integ.cloudevents.cell}
 * @param zone múi giờ của "cuối ngày"
 * @param sealKinds loại niêm phong có thể là bản neo trong ngày
 * @param targets mã kết nối theo đích neo, ví dụ {@code KHO_VPTW: NEO_BTC_LGSP}
 * @param minReceipts số biên nhận tối thiểu một ngày
 * @param grace thời gian chờ sau cuối ngày trước khi kiểm đủ biên nhận
 * @param fetchRetry khoảng chờ ban đầu khi ledger-service chưa có gói neo
 * @param ledgerUrl gốc URI của ledger-service
 * @param ledgerTokenPath đường dẫn bí mật mã thông báo gọi ledger-service (tương đối thư mục bí
 *     mật)
 * @param ledgerTimeout thời hạn gọi ledger-service
 * @param period chu kỳ ba việc theo lịch
 * @param batch số dòng mỗi lượt
 */
@ConfigurationProperties(prefix = "okrkpi.integ.anchor")
public record AnchorProperties(
    boolean enabled,
    UUID operatorTenant,
    String cell,
    ZoneId zone,
    Set<String> sealKinds,
    Map<AnchorTarget, String> targets,
    Integer minReceipts,
    Duration grace,
    Duration fetchRetry,
    URI ledgerUrl,
    String ledgerTokenPath,
    Duration ledgerTimeout,
    Duration period,
    Integer batch) {

  public AnchorProperties {
    targets = targets == null ? Map.of() : Map.copyOf(targets);
    minReceipts = minReceipts == null ? 2 : minReceipts;
    ledgerUrl = ledgerUrl == null ? URI.create("http://ledger-service:8080") : ledgerUrl;
    ledgerTimeout = ledgerTimeout == null ? Duration.ofSeconds(10) : ledgerTimeout;
    period = period == null ? Duration.ofSeconds(30) : period;
    batch = batch == null ? 50 : batch;
  }

  AnchorSettings settings(String defaultCell) {
    if (operatorTenant == null) {
      throw new IllegalStateException("Thiếu okrkpi.integ.anchor.operator-tenant");
    }
    return new AnchorSettings(
        TenantId.of(operatorTenant),
        cell == null || cell.isBlank() ? defaultCell : cell,
        zone,
        sealKinds,
        targets.isEmpty() ? Map.of() : new EnumMap<>(targets),
        minReceipts,
        grace,
        fetchRetry);
  }
}
