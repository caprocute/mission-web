package vn.viettel.okrkpi.shared.crypto;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Ngữ cảnh mã hoá/giải mã trường C2 gắn với luồng xử lý hiện tại phục vụ JPA {@link
 * jakarta.persistence.AttributeConverter} ({@link AbstractC2AttributeConverter}).
 *
 * <p>Vì giao diện {@code AttributeConverter.convertToDatabaseColumn(X)} của JPA chỉ truyền giá trị
 * thuộc tính mà không truyền đối tượng thực thể chứa {@code id} dòng hay {@code tenant_id}, luồng
 * đọc/ghi thực thể có cột C2 đặt ngữ cảnh dòng qua {@link #runWithRow} hoặc {@link #callWithRow};
 * {@code tenantId} mặc định tự lấy từ {@link TenantContext#require()} nếu không truyền riêng.
 */
public final class EncryptionContextHolder {

  /** Thông tin ngữ cảnh của dòng đang mã hoá/giải mã. */
  public record RowEncryptionContext(
      UUID tenantId, String rowId, DekPurpose defaultPurpose, String defaultColumn) {

    public RowEncryptionContext {
      Objects.requireNonNull(tenantId, "tenantId");
      if (rowId == null || rowId.isBlank()) {
        throw new CryptoException("rowId trong ngữ cảnh mã hoá C2 không được rỗng");
      }
      rowId = rowId.trim();
    }
  }

  private static final ThreadLocal<RowEncryptionContext> CURRENT = new ThreadLocal<>();
  private static final AtomicReference<FieldEncryptor> ENCRYPTOR_REF = new AtomicReference<>();

  private EncryptionContextHolder() {}

  /** Đăng ký {@link FieldEncryptor} dùng cho các {@link AbstractC2AttributeConverter} của JPA. */
  public static void registerFieldEncryptor(FieldEncryptor encryptor) {
    ENCRYPTOR_REF.set(encryptor);
  }

  /** Lấy {@link FieldEncryptor} đã đăng ký. */
  public static FieldEncryptor requireFieldEncryptor() {
    FieldEncryptor enc = ENCRYPTOR_REF.get();
    if (enc == null) {
      throw new CryptoException("FieldEncryptor chưa được đăng ký vào EncryptionContextHolder");
    }
    return enc;
  }

  /** Ngữ cảnh dòng hiện tại trên luồng, nếu có. */
  public static Optional<RowEncryptionContext> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** Yêu cầu ngữ cảnh dòng hiện tại trên luồng; ném {@link CryptoException} nếu thiếu. */
  public static RowEncryptionContext require() {
    RowEncryptionContext ctx = CURRENT.get();
    if (ctx == null) {
      throw new CryptoException(
          "Thiếu RowEncryptionContext trên luồng khi chuyển đổi thuộc tính JPA @Classified(C2)");
    }
    return ctx;
  }

  /**
   * Chạy {@code work} trong ngữ cảnh mã hoá của dòng {@code rowId}, lấy {@code tenantId} từ {@link
   * TenantContext#require()}.
   */
  public static void runWithRow(
      String rowId, DekPurpose defaultPurpose, String defaultColumn, Runnable work) {
    TenantId tenant = TenantContext.require();
    runWithRow(
        new RowEncryptionContext(tenant.value(), rowId, defaultPurpose, defaultColumn), work);
  }

  /**
   * Chạy {@code work} trong ngữ cảnh mã hoá {@code context}, khôi phục ngữ cảnh cũ ở {@code
   * finally}.
   */
  public static void runWithRow(RowEncryptionContext context, Runnable work) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(work, "work");
    RowEncryptionContext previous = CURRENT.get();
    CURRENT.set(context);
    try {
      work.run();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /** Như {@link #runWithRow(RowEncryptionContext, Runnable)} nhưng trả về giá trị. */
  public static <T> T callWithRow(RowEncryptionContext context, Callable<T> work) throws Exception {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(work, "work");
    RowEncryptionContext previous = CURRENT.get();
    CURRENT.set(context);
    try {
      return work.call();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /** Dựng danh sách thành phần AAD {@code [columnCoordinate, rowId]} từ ngữ cảnh hiện hành. */
  public static List<String> buildAadParts(String converterColumn) {
    RowEncryptionContext ctx = require();
    String col =
        converterColumn != null && !converterColumn.isBlank()
            ? converterColumn.trim()
            : ctx.defaultColumn();
    if (col == null || col.isBlank()) {
      throw new CryptoException("Thiếu toạ độ cột schema.bảng.cột cho thuộc tính @Classified(C2)");
    }
    return List.of(col, ctx.rowId());
  }
}
