package vn.viettel.okrkpi.analytics.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.analytics.domain.AnalyticsRequest;
import vn.viettel.okrkpi.analytics.domain.Column;
import vn.viettel.okrkpi.analytics.domain.Dashboard;
import vn.viettel.okrkpi.analytics.domain.DashboardWidget;
import vn.viettel.okrkpi.analytics.domain.QueryResult;
import vn.viettel.okrkpi.analytics.domain.ReportDoc;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.TraceChain;
import vn.viettel.okrkpi.analytics.domain.TraceLink;
import vn.viettel.okrkpi.analytics.domain.TraceObjectType;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.analytics.domain.WidgetData;

/**
 * DV-15-06 · tác vụ dài 202 của SVC-15: {@code exportTraceChain} (SCR-1603) và {@code
 * exportAnalyticsReport} (SCR-1601, QT-16 bước 8).
 *
 * <ul>
 *   <li>Kiểm quyền và dựng chuỗi hoặc phạm vi ngay khi nhận (403, 404, 422 trả đồng bộ), phần dựng
 *       tệp chạy nền rồi lưu kho đối tượng; kết quả tải ở {@code resultLocation}.
 *   <li>Chuỗi bằng chứng chỉ gồm mắt xích người xuất có quyền (mắt xích bị che giữ dạng che), kèm
 *       SHA-256 của từng mắt xích và của cả chuỗi; việc xuất ghi {@code sys.audit.v1}.
 *   <li>Cùng người gọi và cùng {@code Idempotency-Key} trả lại đúng tác vụ cũ.
 * </ul>
 */
public class ExportService {

  private static final Logger LOG = LoggerFactory.getLogger(ExportService.class);

  /** Mẫu báo cáo bảng điều hành (mã là suy luận của hợp đồng). */
  public static final String TEMPLATE_BANG_DIEU_HANH = "BANG_DIEU_HANH";

  /** Chỉ số của bảng điều hành mặc định (SCR-1601, như DV-15-03). */
  static final List<String> DASHBOARD_MEASURES =
      List.of(
          "task.ty_le_dung_han", "task.so_viec_cham", "okr.tien_do_o", "appraisal.cho_tham_dinh");

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy")
          .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

  private final TraceService traces;
  private final QueryService queries;
  private final DashboardService dashboards;
  private final ScopeService scopes;
  private final SemanticModelService models;
  private final ExportPorts.Files files;
  private final ExportPorts.Store store;
  private final ExportPorts.Jobs jobs;
  private final ExportPorts.Audit audit;
  private final Executor executor;
  private final Clock clock;

  public ExportService(
      TraceService traces,
      QueryService queries,
      DashboardService dashboards,
      ScopeService scopes,
      SemanticModelService models,
      ExportPorts.Files files,
      ExportPorts.Store store,
      ExportPorts.Jobs jobs,
      ExportPorts.Audit audit,
      Executor executor,
      Clock clock) {
    this.traces = traces;
    this.queries = queries;
    this.dashboards = dashboards;
    this.scopes = scopes;
    this.models = models;
    this.files = files;
    this.store = store;
    this.jobs = jobs;
    this.audit = audit;
    this.executor = executor;
    this.clock = clock;
  }

  /** Yêu cầu báo cáo đã kiểm cú pháp. */
  public record ReportRequest(
      String templateCode,
      UUID periodId,
      UUID unitId,
      UUID dashboardId,
      boolean compareToPreviousPeriod,
      String format) {}

  /** Rỗng khi đối tượng không có (404). */
  public Optional<ExportJob> exportTrace(
      Viewer viewer,
      String bearer,
      TraceObjectType type,
      UUID id,
      List<String> formats,
      String idempotencyKey) {
    Optional<ExportJob> again = replay(viewer, idempotencyKey);
    if (again.isPresent()) {
      return again;
    }
    Optional<TraceChain> chain = traces.chain(viewer, bearer, type, id);
    if (chain.isEmpty()) {
      return Optional.empty();
    }
    List<String> fmts = formats.stream().distinct().toList();
    return Optional.of(
        start(
            viewer,
            idempotencyKey,
            "chuỗi bằng chứng",
            type.name() + ":" + id,
            fmts,
            job -> traceFiles(chain.get(), fmts, job)));
  }

  public ExportJob exportReport(Viewer viewer, ReportRequest request, String idempotencyKey) {
    Optional<ExportJob> again = replay(viewer, idempotencyKey);
    if (again.isPresent()) {
      return again.get();
    }
    if (!TEMPLATE_BANG_DIEU_HANH.equals(request.templateCode())) {
      throw AnalyticsErrors.validation("templateCode", "Mẫu báo cáo không có");
    }
    // Kiểm phạm vi ngay: đơn vị ngoài nhánh trả 403 đồng bộ.
    Scope scope = scopes.resolve(viewer, request.unitId());
    Dashboard dashboard =
        request.dashboardId() == null
            ? null
            : dashboards.get(viewer, request.dashboardId(), null, null, false).dashboard();
    return start(
        viewer,
        idempotencyKey,
        "báo cáo bảng điều hành",
        request.templateCode() + ":" + request.periodId(),
        List.of(request.format()),
        job -> reportFile(viewer, scope, request, dashboard));
  }

  /**
   * Tác vụ của tenant cho {@code getJob}; {@code resultLocation} đổi từ khoá đối tượng sang URL tải
   * ký mới (URL cũ có thể đã hết hạn).
   */
  public Optional<ExportJob> job(UUID tenant, String id) {
    return jobs.find(tenant, id)
        .map(
            j ->
                j.resultLocation() == null
                    ? j
                    : j.next(
                        j.status(),
                        j.progress(),
                        j.message(),
                        store.downloadUrl(j.resultLocation()),
                        j.errorCode(),
                        j.updatedAt()));
  }

  private Optional<ExportJob> replay(Viewer viewer, String key) {
    return jobs.keyed(viewer.tenant(), viewer.userId(), key)
        .flatMap(id -> jobs.find(viewer.tenant(), id));
  }

  private interface Producer {
    Result produce(ExportJob job);
  }

  private record Result(String name, byte[] content, String contentType) {}

  private ExportJob start(
      Viewer viewer,
      String key,
      String what,
      String objectRef,
      List<String> formats,
      Producer producer) {
    String id = UUID.randomUUID().toString();
    ExportJob accepted =
        new ExportJob(
            id,
            viewer.tenant(),
            viewer.userId(),
            ExportJob.Status.CHO,
            0,
            null,
            null,
            null,
            clock.instant());
    jobs.save(accepted);
    jobs.bind(viewer.tenant(), viewer.userId(), key, id);
    try {
      executor.execute(
          () -> {
            boolean ok = run(accepted, producer);
            audit.exported(viewer, what, objectRef, formats, id, ok);
          });
    } catch (RejectedExecutionException e) {
      ExportJob busy =
          accepted.next(
              ExportJob.Status.THAT_BAI,
              100,
              "Hệ thống đang bận, vui lòng thử lại sau",
              null,
              "503",
              clock.instant());
      jobs.save(busy);
      return busy;
    }
    return accepted;
  }

  private boolean run(ExportJob accepted, Producer producer) {
    ExportJob job =
        accepted.next(ExportJob.Status.DANG_CHAY, 10, "Đang dựng tệp", null, null, clock.instant());
    jobs.save(job);
    try {
      Result r = producer.produce(job);
      String key =
          store.save(job.tenant(), job.id() + "/" + r.name(), r.content(), r.contentType());
      jobs.save(
          job.next(ExportJob.Status.HOAN_TAT, 100, "Đã xuất xong", key, null, clock.instant()));
      return true;
    } catch (RuntimeException e) {
      LOG.warn("Tác vụ xuất {} thất bại: {}", job.id(), e.toString());
      jobs.save(
          job.next(
              ExportJob.Status.THAT_BAI,
              100,
              "Chưa xuất được tệp, vui lòng thử lại sau",
              null,
              "503",
              clock.instant()));
      return false;
    }
  }

  private Result traceFiles(TraceChain chain, List<String> formats, ExportJob job) {
    List<String> hashes = chain.links().stream().map(ExportService::linkHash).toList();
    String chainHash = sha256(String.join("\n", hashes));
    Map<String, byte[]> out = new LinkedHashMap<>();
    if (formats.contains("JSON")) {
      out.put("chuoi-bang-chung.json", files.traceJson(chain, hashes, chainHash));
    }
    if (formats.contains("PDF")) {
      out.put("chuoi-bang-chung.pdf", files.pdf(traceDoc(chain, hashes, chainHash)));
    }
    if (out.size() == 1) {
      var e = out.entrySet().iterator().next();
      return new Result(
          e.getKey(),
          e.getValue(),
          e.getKey().endsWith(".pdf") ? "application/pdf" : "application/json");
    }
    return new Result("chuoi-bang-chung.zip", files.zip(out), "application/zip");
  }

  private Result reportFile(Viewer viewer, Scope scope, ReportRequest r, Dashboard dashboard) {
    List<ReportDoc.Section> sections = new ArrayList<>();
    Instant asOf = null;
    if (dashboard == null) {
      // Chỉ số mặc định chưa có trong phiên bản lớp ngữ nghĩa hiện hành thì bỏ qua (suy luận).
      Map<String, ?> known = models.current(scope).measures();
      List<String> measures = DASHBOARD_MEASURES.stream().filter(known::containsKey).toList();
      if (measures.isEmpty()) {
        sections.add(new ReportDoc.Section("Chỉ số chính", List.of(), List.of()));
      } else {
        QueryResult q =
            queries.query(
                viewer,
                new AnalyticsRequest(
                    measures,
                    List.of(),
                    List.of(),
                    r.periodId(),
                    r.unitId(),
                    null,
                    r.compareToPreviousPeriod(),
                    0));
        asOf = q.dataAsOf();
        sections.add(section("Chỉ số chính", q.columns(), q.rows()));
      }
    } else {
      WidgetData data =
          dashboards.get(viewer, dashboard.id(), r.periodId(), r.unitId(), true).data();
      asOf = data.dataAsOf();
      for (int i = 0; i < data.widgets().size(); i++) {
        WidgetData.Item w = data.widgets().get(i);
        DashboardWidget layout = i < dashboard.layout().size() ? dashboard.layout().get(i) : null;
        String title =
            w.columns().stream()
                .filter(c -> layout != null && c.name().equals(layout.measure()))
                .map(Column::title)
                .findFirst()
                .orElse("Biểu đồ " + (i + 1));
        sections.add(section(title, w.columns(), w.rows()));
      }
    }
    List<String> notes = new ArrayList<>();
    notes.add("Phạm vi: đơn vị và các đơn vị trực thuộc trong quyền xem của người xuất");
    if (asOf != null) {
      notes.add("Số liệu cập nhật lúc " + STAMP.format(asOf));
    }
    notes.add("Xuất lúc " + STAMP.format(clock.instant()));
    String title =
        dashboard == null
            ? "Báo cáo bảng điều hành"
            : "Báo cáo bảng điều hành – " + dashboard.title();
    ReportDoc doc = new ReportDoc(title, notes, sections);
    return "XLSX".equals(r.format())
        ? new Result(
            "bao-cao-bang-dieu-hanh.xlsx",
            files.xlsx(doc),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        : new Result("bao-cao-bang-dieu-hanh.pdf", files.pdf(doc), "application/pdf");
  }

  private static ReportDoc.Section section(
      String title, List<Column> columns, List<List<Object>> rows) {
    List<String> headers =
        columns.stream()
            .map(
                c ->
                    c.name().endsWith(QueryService.PREVIOUS_SUFFIX)
                        ? c.title() + " (kỳ trước)"
                        : c.unit() == null ? c.title() : c.title() + " (" + c.unit() + ")")
            .toList();
    List<List<String>> body = new ArrayList<>();
    for (List<Object> row : rows) {
      List<String> cells = new ArrayList<>();
      for (Object v : row) {
        cells.add(
            v instanceof java.math.BigDecimal d
                ? TraceService.number(d)
                : v instanceof Number n
                    ? TraceService.number(new java.math.BigDecimal(n.toString()))
                    : v == null ? "" : v.toString());
      }
      body.add(cells);
    }
    return new ReportDoc.Section(title, headers, body);
  }

  static ReportDoc traceDoc(TraceChain chain, List<String> hashes, String chainHash) {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 0; i < chain.links().size(); i++) {
      TraceLink l = chain.links().get(i);
      rows.add(
          List.of(
              String.valueOf(i + 1),
              TraceLink.maskedLabel(l.objectType()),
              l.masked() ? "Ngoài phạm vi được xem" : l.label(),
              l.value() == null ? "" : l.value(),
              l.versionNo() == null ? "" : "v" + l.versionNo(),
              Labels.status(l.status()),
              hashes.get(i)));
    }
    return new ReportDoc(
        "Chuỗi bằng chứng",
        List.of(
            "Đối tượng: " + TraceLink.maskedLabel(chain.focusType()),
            "Mã băm của chuỗi (SHA-256): " + chainHash,
            "Mã bản ghi sổ cái: " + (chain.ledgerRef() == null ? "chưa có" : chain.ledgerRef()),
            "Chỉ gồm các mắt xích người xuất có quyền xem; mắt xích ngoài phạm vi để dạng che"),
        List.of(
            new ReportDoc.Section(
                "Các mắt xích",
                List.of("TT", "Loại", "Nội dung", "Giá trị", "Phiên bản", "Trạng thái", "Mã băm"),
                rows)));
  }

  /**
   * SHA-256 của dạng chuẩn hoá một mắt xích: các trường nối bằng {@code |}, giá trị thiếu là chuỗi
   * rỗng, chi tiết bỏ ra ngoài (dạng chuẩn hoá là suy luận, ghi ở wiki/14).
   */
  public static String linkHash(TraceLink l) {
    String canonical =
        String.join(
            "|",
            l.objectType().name(),
            l.id().toString(),
            l.masked() ? "" : l.label(),
            nz(l.value()),
            nz(l.status()),
            l.versionNo() == null ? "" : l.versionNo().toString(),
            String.valueOf(l.masked()));
    return sha256(canonical);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
