package vn.viettel.okrkpi.analytics.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.application.ExportService;
import vn.viettel.okrkpi.analytics.domain.AnalyticsRequest;
import vn.viettel.okrkpi.analytics.domain.Dashboard;
import vn.viettel.okrkpi.analytics.domain.DashboardPage;
import vn.viettel.okrkpi.analytics.domain.DashboardWidget;
import vn.viettel.okrkpi.analytics.domain.Filter;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;
import vn.viettel.okrkpi.analytics.domain.TraceChain;
import vn.viettel.okrkpi.analytics.domain.TraceLink;
import vn.viettel.okrkpi.analytics.domain.TraceObjectType;
import vn.viettel.okrkpi.analytics.domain.WidgetData;

/**
 * Thân yêu cầu, phản hồi của các thao tác {@code /analytics/*} theo {@code platform.yaml}. Ràng
 * buộc cú pháp ở đây (lỗi trả 422 {@code COMMON-422-VALIDATION}); tên chỉ số, chiều kiểm theo lớp
 * ngữ nghĩa ở tầng ứng dụng.
 */
final class WebDtos {

  private WebDtos() {}

  record FilterBody(
      @NotBlank String member,
      @NotNull @Pattern(regexp = "equals|notEquals|in|gte|lte") String operator,
      @NotNull List<Object> values) {

    Filter toDomain() {
      return new Filter(member, operator, values);
    }
  }

  record QueryBody(
      @NotNull @Size(min = 1, max = 10) List<@NotBlank String> measures,
      @Size(max = 2) List<@NotBlank String> dimensions,
      @Valid List<FilterBody> filters,
      UUID periodId,
      UUID unitId,
      @Pattern(regexp = "week|month|quarter|year") String timeGranularity,
      Boolean compareToPreviousPeriod,
      @Min(1) @Max(200) Integer limit) {

    AnalyticsRequest toDomain() {
      return new AnalyticsRequest(
          measures,
          dimensions,
          toFilters(filters),
          periodId,
          unitId,
          timeGranularity,
          Boolean.TRUE.equals(compareToPreviousPeriod),
          limit == null ? AnalyticsRequest.DEFAULT_LIMIT : limit);
    }
  }

  record WidgetBody(
      @NotBlank @Size(max = 32) String id,
      @NotNull @Min(0) Integer x,
      @NotNull @Min(0) Integer y,
      @NotNull @Min(1) Integer w,
      @NotNull @Min(1) Integer h,
      @NotBlank String measure,
      @Size(max = 2) List<@NotBlank String> dimensions,
      @NotNull @Pattern(regexp = "THE_SO|COT|DUONG|THANH|BANG") String chartType,
      @Valid List<FilterBody> filters,
      Boolean drillDown) {

    DashboardWidget toDomain() {
      return new DashboardWidget(
          id,
          x,
          y,
          w,
          h,
          measure,
          dimensions,
          chartType,
          toFilters(filters),
          drillDown == null ? Boolean.TRUE : drillDown);
    }
  }

  record DashboardInputBody(
      @NotBlank @Size(max = 100) String title,
      @NotNull @Size(min = 1, max = 20) @Valid List<WidgetBody> layout) {

    List<DashboardWidget> widgets() {
      return layout.stream().map(WidgetBody::toDomain).toList();
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record TraceChainBody(Focus focus, List<TraceLinkBody> links, String ledgerRef) {

    record Focus(String objectType, UUID id) {}

    static TraceChainBody of(TraceChain c) {
      return new TraceChainBody(
          new Focus(c.focusType().name(), c.focusId()),
          c.links().stream().map(TraceLinkBody::of).toList(),
          c.ledgerRef());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record TraceLinkBody(
      String objectType,
      UUID id,
      String label,
      String value,
      String status,
      Integer versionNo,
      String unitName,
      boolean masked,
      String msg,
      java.util.Map<String, Object> details) {

    static TraceLinkBody of(TraceLink l) {
      return new TraceLinkBody(
          l.objectType().name(),
          l.id(),
          l.label(),
          l.value(),
          l.status(),
          l.versionNo(),
          l.unitName(),
          l.masked(),
          l.msg(),
          l.details());
    }
  }

  record TraceExportBody(
      @NotNull TraceObjectType objectType,
      @NotNull UUID id,
      @NotNull @Size(min = 1, max = 2) List<@NotNull @Pattern(regexp = "JSON|PDF") String> formats) {}

  record ReportBody(
      @NotBlank @Size(max = 48) String templateCode,
      @NotNull UUID periodId,
      UUID unitId,
      UUID dashboardId,
      Boolean compareToPreviousPeriod,
      @NotNull @Pattern(regexp = "PDF|XLSX") String format) {

    ExportService.ReportRequest toDomain() {
      return new ExportService.ReportRequest(
          templateCode,
          periodId,
          unitId,
          dashboardId,
          Boolean.TRUE.equals(compareToPreviousPeriod),
          format);
    }
  }

  record EmbedTokenRequestBody(
      @NotBlank @Size(min = 1, max = 80) String dashboardCode, UUID unitId, UUID periodId) {}

  record ShareBody(
      @NotNull @Pattern(regexp = "RIENG|DON_VI|TENANT") String shareScope,
      @Size(max = 200) List<UUID> sharedUnitIds) {}

  record PageBody(
      List<DashboardPage.Summary> items,
      @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

    static PageBody of(DashboardPage page) {
      return new PageBody(page.items(), page.nextCursor());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record DashboardBody(
      UUID id,
      String title,
      UUID ownerUserId,
      List<String> measures,
      List<String> dimensions,
      List<DashboardWidget> layout,
      String shareScope,
      List<UUID> sharedUnitIds,
      String semanticModelVersion,
      long rowVersion,
      Instant updatedAt,
      WidgetData data) {

    /** Thiếu {@code measures}, {@code dimensions} thì suy từ các widget. */
    static DashboardBody of(Dashboard d, WidgetData data) {
      Set<String> ms = new LinkedHashSet<>(d.measures());
      Set<String> ds = new LinkedHashSet<>(d.dimensions());
      if (ms.isEmpty()) {
        d.layout().forEach(w -> ms.add(w.measure()));
      }
      if (ds.isEmpty()) {
        d.layout().forEach(w -> ds.addAll(w.dimensions()));
      }
      return new DashboardBody(
          d.id(),
          d.title(),
          d.ownerUserId(),
          new ArrayList<>(ms),
          new ArrayList<>(ds),
          d.layout(),
          d.shareScope(),
          d.sharedUnitIds(),
          d.semanticModelVersion(),
          d.rowVersion(),
          d.updatedAt(),
          data);
    }
  }

  record ModelBody(String version, List<MeasureBody> measures, List<DimensionBody> dimensions) {

    static ModelBody of(SemanticCatalog c) {
      return new ModelBody(
          c.version(),
          c.measures().values().stream()
              .map(m -> new MeasureBody(m.name(), m.title(), m.type(), m.unit(), m.drillable()))
              .toList(),
          c.dimensions().values().stream()
              .map(
                  d ->
                      new DimensionBody(
                          d.name(),
                          d.title(),
                          d.type(),
                          d.supportedMeasures().stream().sorted().toList()))
              .toList());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record MeasureBody(String name, String title, String type, String unit, boolean drillable) {}

  record DimensionBody(String name, String title, String type, List<String> supportedMeasures) {}

  private static List<Filter> toFilters(List<FilterBody> filters) {
    return filters == null ? List.of() : filters.stream().map(FilterBody::toDomain).toList();
  }
}
