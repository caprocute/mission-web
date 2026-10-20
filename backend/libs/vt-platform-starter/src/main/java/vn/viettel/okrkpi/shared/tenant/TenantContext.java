package vn.viettel.okrkpi.shared.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tenant hiện hành của luồng đang chạy (ADR-07, TL-01 "Lan truyền ngữ cảnh tenant từ JWT tới RLS",
 * chặng ba). Chỉ {@link TenantContextFilter} (tenant đã kiểm từ token) và mã nền của job, consumer
 * (qua {@link #run}, {@link #call}) được đặt; trình quản lý giao dịch đọc để gọi {@code
 * set_config('app.tenant_id', ?, true)}.
 *
 * <p>Lưu trữ nằm sau {@link TenantContextStorage}: mặc định {@code ThreadLocal}, an toàn với luồng
 * ảo vì mỗi luồng ảo có bản sao riêng. TL-01 nêu {@code ScopedValue}, nhưng trên Java 21 đó là API
 * xem trước (cần {@code --enable-preview}); khi nền tảng lên Java 25 có thể thay bằng {@code
 * ScopedValue} mà không đổi mã gọi (điểm lệch, NEN-010).
 */
public final class TenantContext {

  private static volatile TenantContextStorage storage = new ThreadLocalTenantContextStorage();

  private TenantContext() {}

  /** Tenant hiện hành, rỗng khi luồng không có ngữ cảnh tenant. */
  public static Optional<TenantId> current() {
    return Optional.ofNullable(storage.get());
  }

  /** Tenant hiện hành; ném {@link MissingTenantException} khi thiếu. */
  public static TenantId require() {
    TenantId tenant = storage.get();
    if (tenant == null) {
      throw new MissingTenantException();
    }
    return tenant;
  }

  /**
   * Chạy {@code work} trong ngữ cảnh {@code tenant}, khôi phục ngữ cảnh trước đó khi xong (lồng
   * được). Dùng cho job chạy lần lượt từng tenant và consumer Kafka.
   */
  public static void run(TenantId tenant, Runnable work) {
    Objects.requireNonNull(work, "work");
    TenantId previous = enter(tenant);
    try {
      work.run();
    } finally {
      restore(previous);
    }
  }

  /** Như {@link #run} nhưng trả kết quả. */
  public static <T> T call(TenantId tenant, Callable<T> work) throws Exception {
    Objects.requireNonNull(work, "work");
    TenantId previous = enter(tenant);
    try {
      return work.call();
    } finally {
      restore(previous);
    }
  }

  /**
   * Đặt tenant cho luồng và trả tenant trước đó; bên gọi phải {@link #restore} trong {@code
   * finally}. Chỉ dùng ở mã nền (bộ lọc); mã khác dùng {@link #run}.
   */
  static TenantId enter(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    TenantId previous = storage.get();
    storage.set(tenant);
    return previous;
  }

  static void restore(TenantId previous) {
    if (previous == null) {
      storage.clear();
    } else {
      storage.set(previous);
    }
  }

  /** Thay cơ chế lưu (ví dụ {@code ScopedValue} khi lên Java 25); gọi một lần lúc khởi động. */
  public static void useStorage(TenantContextStorage replacement) {
    storage = Objects.requireNonNull(replacement, "replacement");
  }
}
