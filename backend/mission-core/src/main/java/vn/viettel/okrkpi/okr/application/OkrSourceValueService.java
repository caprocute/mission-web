package vn.viettel.okrkpi.okr.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.CheckinCommand;
import vn.viettel.okrkpi.okr.application.OkrCheckinService.SourceTarget;
import vn.viettel.okrkpi.okr.application.port.KrSourceValuePort;
import vn.viettel.okrkpi.okr.application.port.KrSourceValuePort.SourceValue;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Số liệu nguồn của KR (CORE-01-10, QT-03 bước 3, BR-03-04). Lấy giá trị gợi ý qua SVC-13 ngoài
 * giao dịch CSDL và giữ đệm ngắn hạn theo KR và người lấy. Khi gửi check-in, giá trị nguồn do
 * client gửi lại chỉ là gợi ý: máy chủ thay bằng giá trị trong đệm (hết đệm thì lấy lại), nên
 * {@code sourceValue} bị sửa ở trình duyệt thành ra ghi đè và phải có lý do (suy luận). Không lấy
 * được nguồn thì check-in là nhập tay ({@code source_value} rỗng), không bị chặn.
 */
public class OkrSourceValueService {

  static final String UNAVAILABLE = "OKR-502-SOURCE-UNAVAILABLE";
  static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
  static final long MAX_ENTRIES = 100_000;

  private record Key(UUID tenant, UUID keyResult, UUID actor) {}

  /** Tra KR và nguồn của người gọi; bean thật là {@link OkrCheckinService#sourceTarget}. */
  @FunctionalInterface
  public interface SourceTargets {
    Optional<SourceTarget> find(TenantId tenant, UUID actor, UUID keyResultId);
  }

  private final SourceTargets checkins;
  private final Supplier<KrSourceValuePort> port;
  private final Cache<Key, SourceValue> recent;

  public OkrSourceValueService(
      SourceTargets checkins, Supplier<KrSourceValuePort> port, Duration ttl) {
    this(checkins, port, ttl, Ticker.systemTicker());
  }

  OkrSourceValueService(
      SourceTargets checkins, Supplier<KrSourceValuePort> port, Duration ttl, Ticker ticker) {
    this.checkins = Objects.requireNonNull(checkins, "checkins");
    this.port = Objects.requireNonNull(port, "port");
    Duration t = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    this.recent =
        Caffeine.newBuilder()
            .expireAfterWrite(t)
            .maximumSize(MAX_ENTRIES)
            .ticker(Objects.requireNonNull(ticker, "ticker"))
            .build();
  }

  /**
   * {@code getOkrKeyResultSourceValue}: KR phải gắn nguồn, nếu không là không tìm thấy.
   *
   * @throws BusinessException {@code OKR-502-SOURCE-UNAVAILABLE} (MSG-03-03) khi nguồn lỗi
   */
  public SourceValue fetch(TenantId tenant, UUID actor, UUID keyResultId) {
    SourceTarget target =
        checkins.find(tenant, actor, keyResultId).orElseThrow(OkrCheckinService.NotFound::new);
    return fetch(tenant, actor, target);
  }

  /**
   * Thay giá trị nguồn của lệnh check-in bằng giá trị máy chủ tin cậy. Lệnh không kèm giá trị
   * nguồn, KR không gắn nguồn hoặc nguồn lỗi thì là nhập tay.
   */
  public CheckinCommand verify(TenantId tenant, UUID actor, UUID keyResultId, CheckinCommand cmd) {
    if (cmd.sourceValue() == null) {
      return cmd.withSource(null, null);
    }
    Optional<SourceTarget> target = checkins.find(tenant, actor, keyResultId);
    if (target.isEmpty()) {
      return cmd.withSource(null, null);
    }
    SourceValue trusted = recent.getIfPresent(new Key(tenant.value(), keyResultId, actor));
    if (trusted == null) {
      try {
        trusted = fetch(tenant, actor, target.get());
      } catch (BusinessException e) {
        return cmd.withSource(null, null);
      }
    }
    return cmd.withSource(trusted.value(), trusted.fetchedAt());
  }

  private SourceValue fetch(TenantId tenant, UUID actor, SourceTarget target) {
    KrSourceValuePort p = port.get();
    if (p == null) {
      throw unavailable(target.dataSource());
    }
    SourceValue v;
    try {
      v = p.fetch(tenant, target.keyResultId(), target.dataSource());
    } catch (KrSourceValuePort.Unavailable e) {
      throw unavailable(target.dataSource());
    }
    SourceValue out =
        new SourceValue(
            v.value(),
            v.fetchedAt(),
            v.dataSource() == null ? target.dataSource() : v.dataSource());
    recent.put(new Key(tenant.value(), target.keyResultId(), actor), out);
    return out;
  }

  /** Lỗi của nguồn không gắn vào ngoại lệ nghiệp vụ; adapter đã ghi chỉ số và nhật ký. */
  private static BusinessException unavailable(String source) {
    return new BusinessException(
        UNAVAILABLE, "Không lấy được số liệu từ " + source + ". Đồng chí có thể nhập tay.");
  }
}
