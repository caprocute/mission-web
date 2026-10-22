package vn.viettel.okrkpi.config.infrastructure.web;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.HolidayCalendarService;
import vn.viettel.okrkpi.config.domain.HolidayDay;
import vn.viettel.okrkpi.contract.config.model.HolidayCalendarDayInput;
import vn.viettel.okrkpi.contract.config.model.HolidayCalendarDayPage;
import vn.viettel.okrkpi.contract.config.model.HolidayDayKind;
import vn.viettel.okrkpi.contract.config.model.HolidayScope;
import vn.viettel.okrkpi.contract.config.model.WorkingCalendar;
import vn.viettel.okrkpi.contract.config.model.WorkingCalendarDaysInner;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Lịch làm việc của {@code config.yaml} (QT-08, SCR-0801, BR-06-01): {@code getWorkingCalendar} cho
 * mọi người dùng của tenant theo ma trận API, bốn thao tác lịch nghỉ cho quyền {@code
 * SCR-0801.tao-ky}.
 */
@RestController
@RequestMapping("/api/v1/config")
public class HolidayCalendarController {

  private static final String DAYS = "/api/v1/config/holiday-calendars";

  private final HolidayCalendarService calendars;
  private final CursorCodec cursors;
  private final Callers callers;
  private final ConfigIdempotency idempotency;

  public HolidayCalendarController(
      HolidayCalendarService calendars,
      CursorCodec cursors,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency) {
    this.calendars = Objects.requireNonNull(calendars, "calendars");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
  }

  @GetMapping("/calendar")
  @RequiresPermission(operationId = "getWorkingCalendar", module = "config")
  public ResponseEntity<WorkingCalendar> getWorkingCalendar(
      @RequestParam("year") int year,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    HolidayCalendarService.CalendarYear cal = calendars.calendarYear(TenantContext.require(), year);
    return ETags.conditional(
        ifNoneMatch,
        cal.version(),
        () -> {
          WorkingCalendar dto =
              new WorkingCalendar(
                  cal.year(),
                  cal.days().stream()
                      .map(
                          d ->
                              new WorkingCalendarDaysInner(
                                  d.date(),
                                  HolidayDayKind.fromValue(d.kind().name()),
                                  d.name(),
                                  HolidayScope.fromValue(d.scope().name())))
                      .toList());
          dto.setWeekendDays(List.of(6, 7));
          dto.setWorkingDaysByMonth(Arrays.stream(cal.workingDaysByMonth()).boxed().toList());
          return dto;
        });
  }

  @GetMapping("/holiday-calendars")
  @RequiresPermission(
      action = "SCR-0801.tao-ky",
      operationId = "listHolidayCalendarDays",
      module = "config",
      resourceType = "holiday_calendar")
  public HolidayCalendarDayPage listHolidayCalendarDays(
      @RequestParam("year") int year,
      @RequestParam(value = "scope", required = false) HolidayScope scope,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    PageRequest page =
        cursors.pageRequest(limit, cursor, "holiday-calendars:" + year + ":" + scope);
    List<HolidayDay> all =
        calendars.days(
            TenantContext.require(),
            year,
            scope == null
                ? null
                : vn.viettel.okrkpi.config.domain.HolidayScope.valueOf(scope.getValue()));
    List<HolidayDay> fetched =
        page
            .afterKey()
            .map(
                k ->
                    all.stream()
                        .filter(
                            d ->
                                d.date().isAfter(k.getDate(0))
                                    || (d.date().equals(k.getDate(0))
                                        && d.id().compareTo(k.getUuid(1)) > 0))
                        .toList())
            .orElse(all)
            .stream()
            .limit(page.fetchSize())
            .toList();
    CursorPage<HolidayDay> result =
        cursors.page(fetched, page, d -> CursorKey.of(d.date(), d.id()));
    return new HolidayCalendarDayPage(
        result.items().stream().map(HolidayCalendarController::toDto).toList(),
        result.nextCursor());
  }

  @PostMapping("/holiday-calendars")
  @RequiresPermission(
      action = "SCR-0801.tao-ky",
      operationId = "createHolidayCalendarDay",
      module = "config",
      resourceType = "holiday_calendar")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.HolidayCalendarDay>
      createHolidayCalendarDay(
          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
          @RequestBody HolidayCalendarDayInput body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        DAYS,
        body,
        vn.viettel.okrkpi.contract.config.model.HolidayCalendarDay.class,
        vn.viettel.okrkpi.contract.config.model.HolidayCalendarDay::getRowVersion,
        () -> {
          HolidayDay day =
              calendars.create(tenant, actor, body.getCalendarDate(), kind(body), body.getName());
          return ResponseEntity.created(URI.create(DAYS + "/" + day.id()))
              .eTag(ETags.of(day.rowVersion()))
              .body(toDto(day));
        });
  }

  @PutMapping("/holiday-calendars/{id}")
  @RequiresPermission(
      action = "SCR-0801.tao-ky",
      operationId = "updateHolidayCalendarDay",
      module = "config",
      resourceType = "holiday_calendar")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.HolidayCalendarDay>
      updateHolidayCalendarDay(
          @PathVariable("id") UUID id,
          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
          @RequestHeader(value = "If-Match", required = false) String ifMatch,
          @RequestBody HolidayCalendarDayInput body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        DAYS + "/" + id,
        body,
        vn.viettel.okrkpi.contract.config.model.HolidayCalendarDay.class,
        vn.viettel.okrkpi.contract.config.model.HolidayCalendarDay::getRowVersion,
        () -> {
          HolidayDay day =
              notFoundAs404(
                  () ->
                      calendars.update(
                          tenant,
                          actor,
                          id,
                          expected,
                          body.getCalendarDate(),
                          kind(body),
                          body.getName()));
          return ETags.ok(toDto(day), day.rowVersion());
        });
  }

  @DeleteMapping("/holiday-calendars/{id}")
  @RequiresPermission(
      action = "SCR-0801.tao-ky",
      operationId = "deleteHolidayCalendarDay",
      module = "config",
      resourceType = "holiday_calendar")
  public ResponseEntity<Void> deleteHolidayCalendarDay(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "DELETE",
        DAYS + "/" + id,
        null,
        Void.class,
        v -> 0L,
        () -> {
          notFoundAs404(
              () -> {
                calendars.delete(tenant, id, expected);
                return null;
              });
          return ResponseEntity.noContent().build();
        });
  }

  private static <T> T notFoundAs404(java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static vn.viettel.okrkpi.config.domain.HolidayDayKind kind(HolidayCalendarDayInput in) {
    return in.getDayKind() == null
        ? null
        : vn.viettel.okrkpi.config.domain.HolidayDayKind.valueOf(in.getDayKind().getValue());
  }

  static vn.viettel.okrkpi.contract.config.model.HolidayCalendarDay toDto(HolidayDay d) {
    return new vn.viettel.okrkpi.contract.config.model.HolidayCalendarDay(
        d.id(),
        d.date(),
        HolidayDayKind.fromValue(d.kind().name()),
        d.name(),
        HolidayScope.fromValue(d.scope().name()),
        d.rowVersion());
  }
}
