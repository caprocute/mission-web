package vn.viettel.okrkpi.integ.infrastructure.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.integ.application.KrSourceValueService;
import vn.viettel.okrkpi.integ.application.KrSourceValueService.SourceValue;
import vn.viettel.okrkpi.integ.application.MappingCommands;
import vn.viettel.okrkpi.integ.application.MappingConflictException;
import vn.viettel.okrkpi.integ.application.SourceUnavailableException;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng nội bộ số liệu nguồn của KR cho MOD-01 (DV-13-08, QT-03 BR-03-04, API công khai {@code
 * getOkrKeyResultSourceValue} do MOD-01 phục vụ, ≤ 3 giây) và ghi ánh xạ mã ngoài: chỉ token dịch
 * vụ (chuỗi bảo mật {@code /internal/**}). Tenant đi ở tiêu đề {@code X-Tenant-Id} như máy khách
 * {@code HttpKrSourceValues} của MOD-01 gửi, hoặc tham số {@code tenantId} như các cổng nội bộ khác
 * (suy luận, hợp đồng nội bộ chưa có trong {@code contracts/openapi}). Nguồn lỗi hay quá hạn trả
 * 502 để MOD-01 trả {@code OKR-502-SOURCE-UNAVAILABLE}; KR chưa gắn chỉ số trả 404.
 */
@RestController
@RequestMapping("/internal/v1/integ")
@ConditionalOnWebApplication
public class InternalKrSourceValueController {

  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String METRIC = "integ.kr_source.read";

  private static final Logger LOG = LoggerFactory.getLogger(InternalKrSourceValueController.class);

  private final KrSourceValueService sources;
  private final MappingCommands mappings;
  private final MeterRegistry meters;

  public InternalKrSourceValueController(
      KrSourceValueService sources,
      MappingCommands mappings,
      ObjectProvider<MeterRegistry> meters) {
    this.sources = sources;
    this.mappings = mappings;
    this.meters = meters.getIfAvailable();
  }

  /** Thân trả lời đúng tên trường mà {@code HttpKrSourceValues} của MOD-01 đọc. */
  public record SourceValueDto(BigDecimal value, Instant fetchedAt, String dataSource) {}

  /** Ánh xạ mã ngoài; {@code personId} giữ cho org-service (DV-04-07) khi loại là nhân sự. */
  public record MappingRequest(
      UUID tenantId,
      String connector,
      String entityType,
      String externalId,
      UUID internalId,
      UUID personId,
      LocalDate validFrom,
      Boolean replace) {}

  @GetMapping("/kr-source-values")
  public SourceValueDto krSourceValue(
      @RequestHeader(name = TENANT_HEADER, required = false) UUID tenantHeader,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam UUID keyResultId,
      @RequestParam(required = false) String dataSource) {
    TenantId tenant = tenant(tenantHeader, tenantId);
    long start = System.nanoTime();
    String outcome = "error";
    String connector = "none";
    try {
      SourceValue v = sources.read(tenant, keyResultId, dataSource);
      outcome = "ok";
      connector = v.connectorCode();
      return new SourceValueDto(v.value(), v.fetchedAt(), v.dataSource());
    } catch (SourceUnavailableException e) {
      outcome = e.reason().name().toLowerCase(Locale.ROOT);
      LOG.warn("Không lấy được số liệu nguồn của KR {}: {}", keyResultId, e.getMessage());
      throw e;
    } catch (NoSuchElementException e) {
      outcome = "not_found";
      throw e;
    } finally {
      if (meters != null) {
        Timer.builder(METRIC)
            .tag("outcome", outcome)
            .tag("connector", connector)
            .register(meters)
            .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
      }
    }
  }

  @PutMapping("/mapping-xref")
  public ResponseEntity<Void> mapXref(@RequestBody MappingRequest body) {
    if (body == null || body.tenantId() == null || body.connector() == null) {
      throw new IllegalArgumentException("Thiếu tenant hoặc kết nối");
    }
    EntityType type;
    try {
      type =
          body.entityType() == null
              ? EntityType.NHAN_SU
              : EntityType.valueOf(body.entityType().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Loại thực thể không hợp lệ");
    }
    UUID internal = body.internalId() != null ? body.internalId() : body.personId();
    mappings.link(
        TenantId.of(body.tenantId()),
        body.connector(),
        type,
        body.externalId(),
        internal,
        body.validFrom(),
        Boolean.TRUE.equals(body.replace()));
    return ResponseEntity.noContent().build();
  }

  private static TenantId tenant(UUID header, UUID param) {
    UUID t = header != null ? header : param;
    if (t == null) {
      throw new IllegalArgumentException("Thiếu tenant");
    }
    if (header != null && param != null && !header.equals(param)) {
      throw new IllegalArgumentException("Tenant ở tiêu đề và tham số khác nhau");
    }
    return TenantId.of(t);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  ProblemDetail notFound(NoSuchElementException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(MappingConflictException.class)
  ProblemDetail conflict(MappingConflictException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(SourceUnavailableException.class)
  ProblemDetail unavailable(SourceUnavailableException e) {
    ProblemDetail p =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_GATEWAY, "Hệ nguồn không phản hồi, vui lòng nhập tay");
    p.setProperty("reason", e.reason().name());
    return p;
  }
}
