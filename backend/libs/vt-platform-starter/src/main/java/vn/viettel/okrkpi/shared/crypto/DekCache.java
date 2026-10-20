package vn.viettel.okrkpi.shared.crypto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bộ đệm DEK chỉ nằm trong heap của JVM (ADR-06, TL-01 "Bộ đệm DEK"):
 *
 * <ul>
 *   <li>Khoá tra cứu khi mã hoá: {@code (tenant, purpose, month)} ({@link ScopeKey}).
 *   <li>Khoá tra cứu khi giải mã: {@code (tenant, dekId)} ({@link IdKey}).
 *   <li>Thời hạn sống (TTL) tối đa: {@code 15 phút} ({@link #DEFAULT_TTL}).
 *   <li>Sức chứa tối đa: {@code 10.000} mục ({@link #DEFAULT_MAX_SIZE}) theo chính sách LRU.
 *   <li>Khi đẩy mục ra khỏi bộ đệm (hết hạn, vượt sức chứa, thu hồi hoặc xoá toàn bộ), mảng byte
 *       khoá nội bộ được ghi đè bằng {@code 0} ({@link Arrays#fill(byte[], byte)}).
 *   <li>Tuyệt đối không tuần tự hoá, không ghi ra Valkey, nhật ký hay đĩa.
 * </ul>
 */
public final class DekCache implements AutoCloseable {

  /** Thời hạn sống mặc định của mỗi mục DEK trong bộ đệm: 15 phút. */
  public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

  /** Số lượng mục tối đa trong bộ đệm DEK: 10.000 mục. */
  public static final int DEFAULT_MAX_SIZE = 10_000;

  /** Khoá tra cứu DEK theo phạm vi tháng {@code (tenant, purpose, validMonth)}. */
  public record ScopeKey(UUID tenantId, DekPurpose purpose, LocalDate validMonth) {
    public ScopeKey {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(purpose, "purpose");
      Objects.requireNonNull(validMonth, "validMonth");
    }
  }

  /** Khoá tra cứu DEK theo định danh {@code (tenant, dekId)}. */
  public record IdKey(UUID tenantId, UUID dekId) {
    public IdKey {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(dekId, "dekId");
    }
  }

  /**
   * Bản sao an toàn của một DEK lấy từ bộ đệm. Mảng {@code keyBytes} là bản sao phòng thủ; bên gọi
   * nên gọi {@link #zeroKey(byte[])} sau khi dùng xong.
   */
  public record CachedDek(
      UUID dekId,
      UUID tenantId,
      DekPurpose purpose,
      LocalDate validMonth,
      DekStatus status,
      byte[] keyBytes) {

    public CachedDek {
      Objects.requireNonNull(dekId, "dekId");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(purpose, "purpose");
      Objects.requireNonNull(validMonth, "validMonth");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(keyBytes, "keyBytes");
      if (keyBytes.length != CryptoProvider.KEY_LENGTH_BYTES) {
        throw new IllegalArgumentException(
            "Độ dài DEK phải đúng " + CryptoProvider.KEY_LENGTH_BYTES + " byte");
      }
      keyBytes = keyBytes.clone();
    }

    @Override
    public byte[] keyBytes() {
      return keyBytes.clone();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof CachedDek other)) {
        return false;
      }
      return dekId.equals(other.dekId)
          && tenantId.equals(other.tenantId)
          && purpose == other.purpose
          && validMonth.equals(other.validMonth)
          && status == other.status
          && Arrays.equals(keyBytes, other.keyBytes);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(dekId, tenantId, purpose, validMonth, status);
      result = 31 * result + Arrays.hashCode(keyBytes);
      return result;
    }

    @Override
    public String toString() {
      return "CachedDek[dekId="
          + dekId
          + ", tenantId="
          + tenantId
          + ", purpose="
          + purpose
          + ", validMonth="
          + validMonth
          + ", status="
          + status
          + ", keyBytes=[REDACTED]]";
    }
  }

  private static final class Entry {
    private final UUID dekId;
    private final UUID tenantId;
    private final DekPurpose purpose;
    private final LocalDate validMonth;
    private final DekStatus status;
    private final byte[] keyBytes;
    private final Instant expiresAt;
    private boolean wiped;

    private Entry(
        UUID dekId,
        UUID tenantId,
        DekPurpose purpose,
        LocalDate validMonth,
        DekStatus status,
        byte[] keyBytes,
        Instant expiresAt) {
      this.dekId = dekId;
      this.tenantId = tenantId;
      this.purpose = purpose;
      this.validMonth = validMonth;
      this.status = status;
      this.keyBytes = keyBytes.clone();
      this.expiresAt = expiresAt;
    }

    private boolean isExpired(Instant now) {
      return !now.isBefore(expiresAt);
    }

    private CachedDek toCachedDek() {
      return new CachedDek(dekId, tenantId, purpose, validMonth, status, keyBytes);
    }

    private void wipe() {
      if (!wiped) {
        Arrays.fill(keyBytes, (byte) 0);
        wiped = true;
      }
    }
  }

  private final int maxSize;
  private final Duration ttl;
  private final Clock clock;
  private final LinkedHashMap<IdKey, Entry> byId;
  private final Map<ScopeKey, Entry> byScope;
  private int wipedEntryCount;
  private boolean lastWipedAllZero;

  public DekCache() {
    this(DEFAULT_MAX_SIZE, DEFAULT_TTL, Clock.systemUTC());
  }

  public DekCache(int maxSize, Duration ttl, Clock clock) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("maxSize phải > 0");
    }
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl phải > 0");
    }
    this.maxSize = maxSize;
    this.ttl = ttl;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.byId = new LinkedHashMap<>(128, 0.75f, true);
    this.byScope = new HashMap<>(128);
  }

  /**
   * Đưa một DEK vào bộ đệm. Mảng {@code rawKeyBytes} được sao chép vào bộ đệm, không giữ tham chiếu
   * mảng của bên gọi.
   */
  public synchronized void put(
      UUID dekId,
      UUID tenantId,
      DekPurpose purpose,
      LocalDate validMonth,
      DekStatus status,
      byte[] rawKeyBytes) {
    Objects.requireNonNull(dekId, "dekId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(validMonth, "validMonth");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(rawKeyBytes, "rawKeyBytes");
    if (rawKeyBytes.length != CryptoProvider.KEY_LENGTH_BYTES) {
      throw new CryptoException(
          "Độ dài DEK phải đúng " + CryptoProvider.KEY_LENGTH_BYTES + " byte");
    }
    if (!status.canDecrypt()) {
      throw new DekDestroyedException(tenantId, dekId);
    }

    Instant now = clock.instant();
    evictExpiredLocked(now);

    LocalDate normalizedMonth = validMonth.withDayOfMonth(1);
    IdKey idKey = new IdKey(tenantId, dekId);
    ScopeKey scopeKey = new ScopeKey(tenantId, purpose, normalizedMonth);

    Entry previousById = byId.remove(idKey);
    if (previousById != null) {
      ScopeKey prevScope =
          new ScopeKey(previousById.tenantId, previousById.purpose, previousById.validMonth);
      byScope.remove(prevScope, previousById);
      wipeEntryLocked(previousById);
    }

    if (status.canEncrypt()) {
      Entry previousByScope = byScope.remove(scopeKey);
      if (previousByScope != null) {
        byId.remove(new IdKey(previousByScope.tenantId, previousByScope.dekId), previousByScope);
        wipeEntryLocked(previousByScope);
      }
    }

    while (byId.size() >= maxSize) {
      Iterator<Map.Entry<IdKey, Entry>> it = byId.entrySet().iterator();
      if (it.hasNext()) {
        Entry eldest = it.next().getValue();
        it.remove();
        byScope.remove(new ScopeKey(eldest.tenantId, eldest.purpose, eldest.validMonth), eldest);
        wipeEntryLocked(eldest);
      }
    }

    Entry entry =
        new Entry(dekId, tenantId, purpose, normalizedMonth, status, rawKeyBytes, now.plus(ttl));
    byId.put(idKey, entry);
    if (status.canEncrypt()) {
      byScope.put(scopeKey, entry);
    }
  }

  /** Tra cứu DEK đang {@code ACTIVE} theo {@code (tenant, purpose, month)}. */
  public synchronized Optional<CachedDek> getActive(
      UUID tenantId, DekPurpose purpose, LocalDate validMonth) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(validMonth, "validMonth");
    ScopeKey scopeKey = new ScopeKey(tenantId, purpose, validMonth.withDayOfMonth(1));
    Entry entry = byScope.get(scopeKey);
    if (entry == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (entry.isExpired(now) || !entry.status.canEncrypt()) {
      removeEntryLocked(entry);
      return Optional.empty();
    }
    // Cập nhật thứ tự truy cập LRU trong byId
    byId.get(new IdKey(entry.tenantId, entry.dekId));
    return Optional.of(entry.toCachedDek());
  }

  /** Tra cứu DEK theo {@code (tenant, dekId)} để giải mã (bao gồm cả {@code DECRYPT_ONLY}). */
  public synchronized Optional<CachedDek> getById(UUID tenantId, UUID dekId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(dekId, "dekId");
    IdKey idKey = new IdKey(tenantId, dekId);
    Entry entry = byId.get(idKey);
    if (entry == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (entry.isExpired(now)) {
      removeEntryLocked(entry);
      return Optional.empty();
    }
    return Optional.of(entry.toCachedDek());
  }

  /** Thu hồi một DEK khỏi bộ đệm và xoá trắng mảng byte khoá. */
  public synchronized void invalidate(UUID tenantId, UUID dekId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(dekId, "dekId");
    Entry removed = byId.remove(new IdKey(tenantId, dekId));
    if (removed != null) {
      byScope.remove(new ScopeKey(removed.tenantId, removed.purpose, removed.validMonth), removed);
      wipeEntryLocked(removed);
    }
  }

  /** Thu hồi toàn bộ DEK của một tenant và xoá trắng mảng byte khoá. */
  public synchronized void invalidateTenant(UUID tenantId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Iterator<Map.Entry<IdKey, Entry>> it = byId.entrySet().iterator();
    while (it.hasNext()) {
      Entry entry = it.next().getValue();
      if (entry.tenantId.equals(tenantId)) {
        it.remove();
        byScope.remove(new ScopeKey(entry.tenantId, entry.purpose, entry.validMonth), entry);
        wipeEntryLocked(entry);
      }
    }
  }

  /** Xoá toàn bộ bộ đệm và ghi đè {@code 0} lên mọi mảng khoá. */
  public synchronized void clear() {
    for (Entry entry : byId.values()) {
      wipeEntryLocked(entry);
    }
    byId.clear();
    byScope.clear();
  }

  /** Số lượng DEK đang giữ trong bộ đệm (sau khi loại các mục hết hạn). */
  public synchronized int size() {
    evictExpiredLocked(clock.instant());
    return byId.size();
  }

  /** Tiện ích ghi đè {@code 0} lên mảng khoá tạm sau khi mã hoá/giải mã xong. */
  public static void zeroKey(byte[] keyBytes) {
    if (keyBytes != null) {
      Arrays.fill(keyBytes, (byte) 0);
    }
  }

  /** Số lượng mục DEK đã bị đẩy ra và ghi đè {@code 0} lên mảng khoá. */
  public synchronized int wipedEntryCount() {
    return wipedEntryCount;
  }

  /** Kiểm chứng mảng khoá của mục vừa bị đẩy ra đã được ghi đè toàn bộ bằng {@code 0}. */
  public synchronized boolean wasLastEvictedBufferZeroed() {
    return lastWipedAllZero;
  }

  @Override
  public void close() {
    clear();
  }

  @Override
  public String toString() {
    return "DekCache[maxSize=" + maxSize + ", ttl=" + ttl + ", entries=[REDACTED]]";
  }

  private void evictExpiredLocked(Instant now) {
    Iterator<Map.Entry<IdKey, Entry>> it = byId.entrySet().iterator();
    while (it.hasNext()) {
      Entry entry = it.next().getValue();
      if (entry.isExpired(now)) {
        it.remove();
        byScope.remove(new ScopeKey(entry.tenantId, entry.purpose, entry.validMonth), entry);
        wipeEntryLocked(entry);
      }
    }
  }

  private void removeEntryLocked(Entry entry) {
    byId.remove(new IdKey(entry.tenantId, entry.dekId), entry);
    byScope.remove(new ScopeKey(entry.tenantId, entry.purpose, entry.validMonth), entry);
    wipeEntryLocked(entry);
  }

  private void wipeEntryLocked(Entry entry) {
    if (!entry.wiped) {
      entry.wipe();
      wipedEntryCount++;
      boolean allZero = true;
      for (byte b : entry.keyBytes) {
        if (b != 0) {
          allZero = false;
          break;
        }
      }
      lastWipedAllZero = allZero;
    }
  }
}
