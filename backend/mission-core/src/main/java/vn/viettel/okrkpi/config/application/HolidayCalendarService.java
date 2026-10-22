package vn.viettel.okrkpi.config.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;
import vn.viettel.okrkpi.config.api.HolidayCalendarChangedEvent;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.config.application.port.HolidayCalendarRepository;
import vn.viettel.okrkpi.config.domain.HolidayDay;
import vn.viettel.okrkpi.config.domain.HolidayDayKind;
import vn.viettel.okrkpi.config.domain.HolidayScope;
import vn.viettel.okrkpi.config.domain.NationalHolidays;
import vn.viettel.okrkpi.config.domain.WorkingDays;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lịch ngày nghỉ, làm bù của MOD-08 (CORE-08-04, BR-06-01) và thư viện tính ngày làm việc {@link
 * WorkingCalendarApi}. Lịch gộp = lịch quốc gia dựng sẵn ({@link NationalHolidays}) ghi đè bằng các
 * dòng của tenant trong {@code config.holiday_calendar} (dòng {@code QUOC_GIA} do tenant hệ thống
 * nạp, dòng {@code TENANT} do quản trị tenant nhập). Lịch gộp được đệm theo tenant (Caffeine, mặc
 * định 60 giây), xoá ngay khi lịch đổi trong pod và sau khi giao dịch kết thúc.
 */
public class HolidayCalendarService implements WorkingCalendarApi {

  static final String TX = "configTransactionManager";
  static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  /** Lịch gộp của một tenant: các ngày đặc biệt theo ngày và bộ tính ngày làm việc. */
  public record Merged(List<HolidayDay> days, WorkingDays workingDays) {}

  /** Lịch của một năm cho {@code getWorkingCalendar}; {@code version} làm ETag. */
  public record CalendarYear(
      int year, List<HolidayDay> days, int[] workingDaysByMonth, long version) {}

  private final HolidayCalendarRepository repository;
  private final ApplicationEventPublisher events;
  private final TransactionOperations reads;
  private final Cache<TenantId, Merged> cache;

  public HolidayCalendarService(
      HolidayCalendarRepository repository,
      ApplicationEventPublisher events,
      TransactionOperations reads,
      Duration ttl) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.events = Objects.requireNonNull(events, "events");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build();
  }

  // ------------------------------------------------------------------ đọc

  /** Lịch gộp của tenant, đọc qua đệm. */
  public Merged merged(TenantId tenantId) {
    Objects.requireNonNull(tenantId, "tenantId");
    return cache.get(tenantId, t -> reads.execute(s -> load(t)));
  }

  private Merged load(TenantId tenantId) {
    Map<LocalDate, HolidayDay> byDate = new HashMap<>();
    NationalHolidays.all().forEach(d -> byDate.put(d.date(), d));
    repository.all(tenantId).forEach(d -> byDate.put(d.date(), d));
    List<HolidayDay> days =
        byDate.values().stream().sorted(Comparator.comparing(HolidayDay::date)).toList();
    Map<LocalDate, HolidayDayKind> kinds = new HashMap<>();
    days.forEach(d -> kinds.put(d.date(), d.kind()));
    return new Merged(days, new WorkingDays(kinds));
  }

  /** Lịch năm cho màn hình và các phép tính phía web. */
  public CalendarYear calendarYear(TenantId tenantId, int year) {
    requireYear(year);
    Merged m = merged(tenantId);
    List<HolidayDay> days = m.days().stream().filter(d -> d.date().getYear() == year).toList();
    CRC32 crc = new CRC32();
    for (HolidayDay d : days) {
      crc.update(
          (d.date()
                  + "|"
                  + d.kind()
                  + "|"
                  + d.name()
                  + "|"
                  + d.scope()
                  + "|"
                  + d.rowVersion()
                  + "\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    return new CalendarYear(year, days, m.workingDays().workingDaysByMonth(year), crc.getValue());
  }

  /** Ngày đặc biệt của năm (lịch quốc gia kèm lịch riêng), lọc theo phạm vi nếu có. */
  public List<HolidayDay> days(TenantId tenantId, int year, HolidayScope scope) {
    requireYear(year);
    return merged(tenantId).days().stream()
        .filter(d -> d.date().getYear() == year)
        .filter(d -> scope == null || d.scope() == scope)
        .sorted(Comparator.comparing(HolidayDay::date).thenComparing(HolidayDay::id))
        .toList();
  }

  @Override
  public boolean isWorkingDay(TenantId tenantId, LocalDate date) {
    return merged(tenantId).workingDays().isWorkingDay(date);
  }

  @Override
  public LocalDate addWorkingDays(TenantId tenantId, LocalDate from, int n) {
    return merged(tenantId).workingDays().plusWorkingDays(from, n);
  }

  @Override
  public long countWorkingDays(TenantId tenantId, LocalDate fromExclusive, LocalDate toInclusive) {
    return merged(tenantId).workingDays().countWorkingDays(fromExclusive, toInclusive);
  }

  @Override
  public WorkingCalendar celCalendar(TenantId tenantId) {
    Map<LocalDate, WorkingCalendar.DayKind> special = new HashMap<>();
    merged(tenantId)
        .workingDays()
        .specialDays()
        .forEach((d, k) -> special.put(d, WorkingCalendar.DayKind.valueOf(k.name())));
    return StaticWorkingCalendar.of(special);
  }

  // ------------------------------------------------------------------ ghi

  /** {@code createHolidayCalendarDay}: thêm một ngày vào lịch riêng của tenant. */
  @Transactional(TX)
  public HolidayDay create(
      TenantId tenantId, UUID actorId, LocalDate date, HolidayDayKind kind, String name) {
    validate(date, kind, name);
    HolidayDay day =
        new HolidayDay(Ids.newUuidV7(), date, kind, name.strip(), HolidayScope.TENANT, 0);
    try {
      repository.insert(tenantId, day, actorId);
    } catch (HolidayCalendarRepository.DateExistsException e) {
      throw dateExists(date);
    }
    changed(tenantId, date.getYear());
    return day;
  }

  /** {@code updateHolidayCalendarDay}: sửa ngày của lịch riêng khi {@code If-Match} khớp. */
  @Transactional(TX)
  public HolidayDay update(
      TenantId tenantId,
      UUID actorId,
      UUID id,
      long expectedVersion,
      LocalDate date,
      HolidayDayKind kind,
      String name) {
    HolidayDay current = editable(tenantId, id);
    validate(date, kind, name);
    requireVersion(current, expectedVersion);
    HolidayDay next =
        new HolidayDay(id, date, kind, name.strip(), HolidayScope.TENANT, current.rowVersion() + 1);
    boolean ok;
    try {
      ok = repository.update(tenantId, next, current.rowVersion(), actorId);
    } catch (HolidayCalendarRepository.DateExistsException e) {
      throw dateExists(date);
    }
    if (!ok) {
      throw new VersionConflictException("CONFIG", expectedVersion, null);
    }
    changed(tenantId, current.date().getYear());
    if (date.getYear() != current.date().getYear()) {
      changed(tenantId, date.getYear());
    }
    return next;
  }

  /** {@code deleteHolidayCalendarDay}: xoá ngày của lịch riêng khi {@code If-Match} khớp. */
  @Transactional(TX)
  public void delete(TenantId tenantId, UUID id, long expectedVersion) {
    HolidayDay current = editable(tenantId, id);
    requireVersion(current, expectedVersion);
    if (!repository.delete(tenantId, id, current.rowVersion())) {
      throw new VersionConflictException("CONFIG", expectedVersion, null);
    }
    changed(tenantId, current.date().getYear());
  }

  /** Xoá đệm lịch của tenant (khi nhận thông báo lịch đổi từ nơi khác). */
  public void evict(TenantId tenantId) {
    cache.invalidate(tenantId);
  }

  private HolidayDay editable(TenantId tenantId, UUID id) {
    Objects.requireNonNull(id, "id");
    if (NationalHolidays.isBuiltIn(id)) {
      throw nationalReadOnly();
    }
    HolidayDay current =
        repository.findById(tenantId, id).orElseThrow(() -> new NoSuchElementException("id"));
    if (current.scope() == HolidayScope.QUOC_GIA) {
      throw nationalReadOnly();
    }
    return current;
  }

  private static void requireVersion(HolidayDay current, long expected) {
    if (expected != current.rowVersion()) {
      throw VersionConflictException.of("CONFIG", expected, current.rowVersion());
    }
  }

  private static void validate(LocalDate date, HolidayDayKind kind, String name) {
    String error = HolidayDay.validate(date, kind, name);
    if (error != null) {
      throw new BusinessException("COMMON-422-VALIDATION", error);
    }
  }

  private static void requireYear(int year) {
    if (year < 2020 || year > 2100) {
      throw new BusinessException("COMMON-422-VALIDATION", "Năm phải trong khoảng 2020 đến 2100.");
    }
  }

  private static BusinessException dateExists(LocalDate date) {
    return new BusinessException(
        "CONFIG-409-DATE-EXISTS", "Ngày " + DMY.format(date) + " đã có trong lịch của đơn vị.");
  }

  private static BusinessException nationalReadOnly() {
    return new BusinessException(
        "COMMON-403-OUT-OF-SCOPE", "Lịch quốc gia chỉ được cập nhật bởi quản trị hệ thống.");
  }

  private void changed(TenantId tenantId, int year) {
    cache.invalidate(tenantId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              cache.invalidate(tenantId);
            }
          });
    }
    events.publishEvent(new HolidayCalendarChangedEvent(tenantId, year));
  }
}
