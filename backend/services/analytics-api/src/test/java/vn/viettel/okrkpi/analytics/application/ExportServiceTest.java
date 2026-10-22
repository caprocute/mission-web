package vn.viettel.okrkpi.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.viettel.okrkpi.analytics.application.Fakes.KY_Q4;
import static vn.viettel.okrkpi.analytics.application.Fakes.TENANT;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_KHAC;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_TINH;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.analytics.domain.ReportDoc;
import vn.viettel.okrkpi.analytics.domain.TraceChain;
import vn.viettel.okrkpi.analytics.domain.TraceLink;
import vn.viettel.okrkpi.analytics.domain.TraceObjectType;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** DV-15-06: tác vụ xuất, chống lặp theo khoá, mã băm mắt xích, nhãn không lộ mã thô. */
class ExportServiceTest {

  final Clock clock = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC);
  final Fakes.Cube cube = new Fakes.Cube();
  final Fakes.Org org = new Fakes.Org();
  final ScopeService scopes = new ScopeService(org);
  final SemanticModelService models = new SemanticModelService(cube, Duration.ofMinutes(5), clock);
  final QueryService queries =
      new QueryService(
          scopes,
          models,
          cube,
          org,
          new Fakes.Cache(),
          Duration.ZERO,
          Duration.ofMinutes(5),
          clock);
  final Viewer leader = new Viewer(TENANT, UUID.randomUUID(), UNIT_TINH);
  final UUID evidence = UUID.randomUUID();
  final UUID hidden = UUID.randomUUID();

  final TraceChain chain =
      new TraceChain(
          TraceObjectType.MINH_CHUNG,
          evidence,
          List.of(
              new TraceLink(
                  TraceObjectType.MINH_CHUNG,
                  evidence,
                  "Biên bản <nghiệm thu>",
                  null,
                  "DA_XAC_NHAN",
                  2,
                  "Huyện A",
                  false,
                  null,
                  null),
              TraceLink.masked(TraceObjectType.MINH_CHUNG, hidden)),
          null);

  final TraceService traces =
      new TraceService(scopes, org, null, null) {
        @Override
        public Optional<TraceChain> chain(
            Viewer viewer, String bearer, TraceObjectType type, UUID id) {
          return id.equals(evidence) ? Optional.of(chain) : Optional.empty();
        }
      };

  final Map<String, ExportJob> saved = new HashMap<>();
  final Map<String, String> keys = new HashMap<>();
  final Map<String, byte[]> objects = new HashMap<>();
  final List<ReportDoc> pdfs = new ArrayList<>();
  final List<ReportDoc> xlsxs = new ArrayList<>();
  final List<Map<String, byte[]>> zips = new ArrayList<>();
  final List<Object[]> audits = new ArrayList<>();
  boolean failPdf;

  final ExportPorts.Files files =
      new ExportPorts.Files() {
        @Override
        public byte[] pdf(ReportDoc doc) {
          if (failPdf) {
            throw new IllegalStateException("gotenberg");
          }
          pdfs.add(doc);
          return "%PDF".getBytes();
        }

        @Override
        public byte[] xlsx(ReportDoc doc) {
          xlsxs.add(doc);
          return "PK".getBytes();
        }

        @Override
        public byte[] traceJson(TraceChain c, List<String> hashes, String chainHash) {
          return ("{\"chainHash\":\"" + chainHash + "\"}").getBytes();
        }

        @Override
        public byte[] zip(Map<String, byte[]> entries) {
          zips.add(entries);
          return "PK".getBytes();
        }
      };

  final ExportPorts.Store store =
      new ExportPorts.Store() {
        @Override
        public String save(UUID tenant, String name, byte[] content, String type) {
          objects.put(tenant + "/" + name, content);
          return tenant + "/" + name;
        }

        @Override
        public String downloadUrl(String key) {
          return "https://s3.example/" + key + "?ky=1";
        }
      };

  final ExportPorts.Jobs jobs =
      new ExportPorts.Jobs() {
        @Override
        public void save(ExportJob job) {
          saved.put(job.id(), job);
        }

        @Override
        public Optional<ExportJob> find(UUID tenant, String id) {
          return Optional.ofNullable(saved.get(id)).filter(j -> j.tenant().equals(tenant));
        }

        @Override
        public Optional<String> keyed(UUID tenant, UUID user, String key) {
          return Optional.ofNullable(keys.get(tenant + ":" + user + ":" + key));
        }

        @Override
        public void bind(UUID tenant, UUID user, String key, String id) {
          keys.put(tenant + ":" + user + ":" + key, id);
        }
      };

  final DashboardServiceTest.Store dashStore = new DashboardServiceTest.Store();
  final DashboardService dashboards =
      new DashboardService(dashStore, scopes, models, queries, ShareNotifier.NONE);

  final ExportService service = service(Runnable::run);

  ExportService service(java.util.concurrent.Executor executor) {
    return new ExportService(
        traces,
        queries,
        dashboards,
        scopes,
        models,
        files,
        store,
        jobs,
        (v, what, ref, formats, id, ok) -> audits.add(new Object[] {ref, formats, id, ok}),
        executor,
        clock);
  }

  @Test
  void traceExportPacksBothFormatsHashesLinksAndAudits() {
    ExportJob accepted =
        service
            .exportTrace(
                leader,
                "Bearer t",
                TraceObjectType.MINH_CHUNG,
                evidence,
                List.of("JSON", "PDF"),
                "k1")
            .orElseThrow();
    assertThat(accepted.status()).isEqualTo(ExportJob.Status.CHO);
    ExportJob done = service.job(TENANT, accepted.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(ExportJob.Status.HOAN_TAT);
    assertThat(done.resultLocation())
        .startsWith("https://s3.example/" + TENANT + "/" + accepted.id() + "/chuoi-bang-chung.zip");
    assertThat(zips.get(0)).containsOnlyKeys("chuoi-bang-chung.json", "chuoi-bang-chung.pdf");
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0)[0]).isEqualTo("MINH_CHUNG:" + evidence);
    assertThat(audits.get(0)[3]).isEqualTo(true);

    ReportDoc doc = pdfs.get(0);
    List<String> first = doc.sections().get(0).rows().get(0);
    List<String> masked = doc.sections().get(0).rows().get(1);
    assertThat(first).contains("Đã xác nhận").doesNotContain("DA_XAC_NHAN");
    assertThat(masked).contains("Ngoài phạm vi được xem");
    assertThat(first.get(6)).hasSize(64).isEqualTo(ExportService.linkHash(chain.links().get(0)));
    assertThat(String.join(" ", doc.notes())).contains("SHA-256").doesNotContain("MSG-16");

    // Cùng khoá: đúng tác vụ cũ, không dựng lại.
    ExportJob again =
        service
            .exportTrace(
                leader,
                "Bearer t",
                TraceObjectType.MINH_CHUNG,
                evidence,
                List.of("JSON", "PDF"),
                "k1")
            .orElseThrow();
    assertThat(again.id()).isEqualTo(accepted.id());
    assertThat(zips).hasSize(1);
    assertThat(
            service.exportTrace(
                leader,
                "Bearer t",
                TraceObjectType.MINH_CHUNG,
                UUID.randomUUID(),
                List.of("JSON"),
                "k2"))
        .isEmpty();
  }

  @Test
  void linkHashIsStableAndChangesWithContent() {
    TraceLink a = chain.links().get(0);
    TraceLink b =
        new TraceLink(
            a.objectType(),
            a.id(),
            a.label(),
            null,
            a.status(),
            3,
            a.unitName(),
            false,
            null,
            null);
    assertThat(ExportService.linkHash(a)).isEqualTo(ExportService.linkHash(a));
    assertThat(ExportService.linkHash(a)).isNotEqualTo(ExportService.linkHash(b));
  }

  @Test
  void singleFormatIsNotZippedAndFailureIsReportedWithoutRawCode() {
    ExportJob json =
        service
            .exportTrace(leader, "b", TraceObjectType.MINH_CHUNG, evidence, List.of("JSON"), "j")
            .orElseThrow();
    assertThat(service.job(TENANT, json.id()).orElseThrow().resultLocation())
        .contains("chuoi-bang-chung.json");
    assertThat(zips).isEmpty();

    failPdf = true;
    ExportJob pdf =
        service
            .exportTrace(leader, "b", TraceObjectType.MINH_CHUNG, evidence, List.of("PDF"), "p")
            .orElseThrow();
    ExportJob failed = service.job(TENANT, pdf.id()).orElseThrow();
    assertThat(failed.status()).isEqualTo(ExportJob.Status.THAT_BAI);
    assertThat(failed.resultLocation()).isNull();
    assertThat(failed.message()).isEqualTo("Chưa xuất được tệp, vui lòng thử lại sau");
    assertThat(audits.get(audits.size() - 1)[3]).isEqualTo(false);
  }

  @Test
  void reportUsesKnownDefaultMeasuresInViewerScope() {
    ExportJob job =
        service.exportReport(
            leader,
            new ExportService.ReportRequest("BANG_DIEU_HANH", KY_Q4, UNIT_TINH, null, true, "XLSX"),
            "r1");
    assertThat(service.job(TENANT, job.id()).orElseThrow().status())
        .isEqualTo(ExportJob.Status.HOAN_TAT);
    ReportDoc doc = xlsxs.get(0);
    assertThat(doc.title()).isEqualTo("Báo cáo bảng điều hành");
    // Danh mục giả chỉ có okr.tien_do_o trong bộ chỉ số mặc định.
    assertThat(cube.queries.get(cube.queries.size() - 1).get("measures").toString())
        .contains("okr.tien_do_o")
        .doesNotContain("task.ty_le_dung_han");
    assertThat(doc.sections().get(0).headers()).contains("Tiến độ mục tiêu O (%)");
    assertThat(doc.notes()).anyMatch(n -> n.startsWith("Xuất lúc 10:00 ngày 22/10/2026"));

    assertThatThrownBy(
            () ->
                service.exportReport(
                    leader,
                    new ExportService.ReportRequest("KHAC", KY_Q4, null, null, false, "PDF"),
                    "r2"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.exportReport(
                    leader,
                    new ExportService.ReportRequest(
                        "BANG_DIEU_HANH", KY_Q4, UNIT_KHAC, null, false, "PDF"),
                    "r3"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void dashboardReportHasOneSectionPerWidgetWithVietnameseNumbers() {
    cube.rows =
        (scope, q) -> {
          Map<String, Object> r = new HashMap<>();
          r.put("org_unit.ten", "Huyện A");
          r.put("okr.tien_do_o", new java.math.BigDecimal("72.5"));
          r.put("okr.so_muc_tieu", null);
          return List.of(r);
        };
    var d =
        dashboards.create(
            leader,
            "Bảng của Bí thư",
            List.of(
                DashboardServiceTest.widget("w1", "okr.tien_do_o", List.of("org_unit.ten")),
                DashboardServiceTest.widget("w2", "okr.so_muc_tieu", List.of())),
            null);
    ExportJob job =
        service.exportReport(
            leader,
            new ExportService.ReportRequest("BANG_DIEU_HANH", KY_Q4, null, d.id(), false, "PDF"),
            "d1");
    assertThat(service.job(TENANT, job.id()).orElseThrow().resultLocation())
        .contains("bao-cao-bang-dieu-hanh.pdf");
    ReportDoc doc = pdfs.get(0);
    assertThat(doc.title()).isEqualTo("Báo cáo bảng điều hành – Bảng của Bí thư");
    assertThat(doc.sections())
        .extracting(ReportDoc.Section::title)
        .containsExactly("Tiến độ mục tiêu O", "Số mục tiêu O");
    assertThat(doc.sections().get(0).rows().get(0)).containsExactly("Huyện A", "72,5");
    assertThat(doc.sections().get(1).rows().get(0)).containsExactly("");
  }

  @Test
  void fullQueueMarksJobBusyAndUnknownStatusHasNoLabel() {
    ExportService busy =
        service(
            r -> {
              throw new java.util.concurrent.RejectedExecutionException();
            });
    ExportJob job =
        busy.exportTrace(leader, "b", TraceObjectType.MINH_CHUNG, evidence, List.of("PDF"), "q")
            .orElseThrow();
    assertThat(job.status()).isEqualTo(ExportJob.Status.THAT_BAI);
    assertThat(job.message()).isEqualTo("Hệ thống đang bận, vui lòng thử lại sau");
    assertThat(Labels.status("KHONG_RO")).isEmpty();
    assertThat(Labels.status(null)).isEmpty();
    assertThat(Labels.status("CHO_NGHIEM_THU")).isEqualTo("Chờ nghiệm thu");
  }
}
