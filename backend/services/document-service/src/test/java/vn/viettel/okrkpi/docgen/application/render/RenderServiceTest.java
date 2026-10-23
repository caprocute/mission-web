package vn.viettel.okrkpi.docgen.application.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.docgen.domain.render.RenderJob;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobRepository;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobStatus;
import vn.viettel.okrkpi.docgen.domain.render.RenderOutput;
import vn.viettel.okrkpi.docgen.domain.render.Template;
import vn.viettel.okrkpi.docgen.domain.render.TemplateRepository;
import vn.viettel.okrkpi.docgen.domain.render.TemplateStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobStatus;

/** Đường ống sinh với cổng giả (DV-11-01). */
class RenderServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID PACKAGE = UUID.randomUUID();
  private static final byte[] TEMPLATE_DOCX = "mau-docx".getBytes(StandardCharsets.UTF_8);
  private static final Instant T0 = Instant.parse("2026-10-22T04:00:00Z");

  private final MemoryTemplates templates = new MemoryTemplates();
  private final MemoryJobs jobs = new MemoryJobs();
  private final Map<UUID, byte[]> data = new HashMap<>();
  private final List<RenderInputPort.FormRef> forms = new ArrayList<>();
  private final Map<String, byte[]> objects = new HashMap<>();
  private final List<UUID> events = new ArrayList<>();
  private final List<String> outcomes = new ArrayList<>();
  private boolean packageExists = true;
  private boolean converterDown;
  private boolean pdfaCompliant = true;
  private Map<String, String> lastFields;
  private final List<RenderStamp> stamps = new ArrayList<>();
  private Instant now = T0;
  private final Clock clock =
      new Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };
  private Runnable duringConvert = () -> {};
  private RenderService service;

  @BeforeEach
  void setUp() {
    objects.put("templates/mau/1a.docx", TEMPLATE_DOCX);
    templates.items.add(template(1, TemplateStatus.NGUNG, List.of()));
    templates.items.add(template(2, TemplateStatus.HIEU_LUC, List.of("KHOI_A")));
    RenderInputPort inputs =
        new RenderInputPort() {
          @Override
          public Optional<List<FormRef>> forms(
              UUID tenantId, UUID actor, UUID packageId, List<String> codes) {
            return packageExists ? Optional.of(List.copyOf(forms)) : Optional.empty();
          }

          @Override
          public Optional<FormData> data(UUID tenantId, UUID formInstanceId) {
            return Optional.ofNullable(data.get(formInstanceId))
                .map(d -> new FormData(formInstanceId, "MAU_1A", d));
          }
        };
    DocumentPorts.ObjectStore store =
        new DocumentPorts.ObjectStore() {
          @Override
          public byte[] get(String bucket, String key) {
            return objects.get(bucket + "/" + key);
          }

          @Override
          public void put(String bucket, String key, byte[] content, String contentType) {
            objects.put(bucket + "/" + key, content);
          }
        };
    service =
        new RenderService(
            templates,
            jobs,
            inputs,
            List.of(),
            (docx, fields) -> {
              lastFields = fields;
              return ("merged:" + fields).getBytes(StandardCharsets.UTF_8);
            },
            (docx, name) -> {
              duringConvert.run();
              if (converterDown) {
                throw new DocumentPorts.TransientFailure("Gotenberg tạm ngừng", null);
              }
              return ("%PDF-" + new String(docx, StandardCharsets.UTF_8))
                  .getBytes(StandardCharsets.UTF_8);
            },
            pdf -> new DocumentPorts.PdfaValidator.Result(pdfaCompliant, "6.2.11.4.1"),
            (pdf, stamp) -> {
              stamps.add(stamp);
              return pdf;
            },
            store,
            (job, template, output, version) -> events.add(job.id()),
            (code, outcome, took) -> outcomes.add(outcome),
            new ObjectMapper(),
            clock,
            2,
            Duration.ofMinutes(5),
            TransactionOperations.withoutTransaction());
  }

  private static Template template(int version, TemplateStatus status, List<String> groups) {
    return new Template(
        UUID.randomUUID(),
        TENANT,
        "MAU_1A",
        version,
        groups,
        "mau/1a.docx",
        Hashes.sha256(TEMPLATE_DOCX),
        status);
  }

  private UUID form(String json) {
    UUID id = UUID.randomUUID();
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    data.put(id, bytes);
    forms.add(new RenderInputPort.FormRef(id, "MAU_1A", "KHOI_A", Hashes.sha256(bytes)));
    return id;
  }

  private Job request(UUID requestId) {
    return service.request(
        new RenderService.RequestCommand(TENANT, CALLER, PACKAGE, List.of("MAU_1A"), requestId));
  }

  @Test
  void requestCreatesOneWaitingJobPerFormAndResendDoesNotDuplicate() {
    form("{\"hoTen\":\"Nguyễn Văn A\"}");
    form("{\"hoTen\":\"Trần Thị B\"}");
    UUID requestId = UUID.randomUUID();
    Job job = request(requestId);
    assertThat(job.id()).isEqualTo(requestId.toString());
    assertThat(job.type()).isEqualTo("RENDER");
    assertThat(job.status()).isEqualTo(JobStatus.CHO);
    assertThat(job.progress()).isZero();
    request(requestId);
    assertThat(jobs.items).hasSize(2);
    assertThat(jobs.items).allMatch(j -> j.templateId().equals(templates.items.get(1).id()));
  }

  @Test
  void renderedFormIsStoredWithHashedKeyAndOneEvent() {
    UUID formId = form("{\"canBo\":{\"hoTen\":\"Nguyễn Văn A\"},\"mucTieu\":[{\"ten\":\"X\"}]}");
    UUID requestId = UUID.randomUUID();
    request(requestId);
    UUID jobId = jobs.items.get(0).id();

    assertThat(service.processOnce(jobId)).isEqualTo(RenderJobStatus.XONG);
    assertThat(service.processOnce(jobId)).isNull();

    assertThat(lastFields)
        .containsEntry("canBo.hoTen", "Nguyễn Văn A")
        .containsEntry("mucTieu.1.ten", "X");
    RenderOutput output = jobs.outputs.get(0);
    assertThat(output.objectKey())
        .startsWith(TENANT + "/forms/MAU_1A/" + formId + "/")
        .endsWith(java.util.HexFormat.of().formatHex(output.pdfSha256()) + ".pdf");
    assertThat(Hashes.sha256(objects.get("dossier/" + output.objectKey())))
        .isEqualTo(output.pdfSha256());
    assertThat(output.pdfaValidated()).isTrue();
    assertThat(events).containsExactly(jobId);
    Job job = service.job(requestId).orElseThrow();
    assertThat(job.status()).isEqualTo(JobStatus.HOAN_TAT);
    assertThat(job.progress()).isEqualTo(100);
    assertThat(service.initiator(requestId)).contains(CALLER);
    assertThat(outcomes).containsExactly("XONG");
  }

  @Test
  void changedDataIsRejectedWithClearReason() {
    UUID formId = form("{\"hoTen\":\"A\"}");
    request(UUID.randomUUID());
    data.put(formId, "{\"hoTen\":\"B\"}".getBytes(StandardCharsets.UTF_8));
    UUID jobId = jobs.items.get(0).id();

    assertThat(service.processOnce(jobId)).isEqualTo(RenderJobStatus.LOI);
    assertThat(jobs.items.get(0).error()).contains("băm dữ liệu không khớp");
    assertThat(jobs.outputs).isEmpty();
    assertThat(events).isEmpty();
  }

  @Test
  void transientFailureIsRetriedThenFails() {
    form("{}");
    UUID requestId = UUID.randomUUID();
    request(requestId);
    UUID jobId = jobs.items.get(0).id();
    converterDown = true;

    assertThat(service.processOnce(jobId)).isEqualTo(RenderJobStatus.CHO);
    assertThat(service.job(requestId).orElseThrow().status()).isEqualTo(JobStatus.CHO);
    assertThat(service.processOnce(jobId)).isEqualTo(RenderJobStatus.LOI);
    assertThat(jobs.items.get(0).attempts()).isEqualTo(2);
    Job job = service.job(requestId).orElseThrow();
    assertThat(job.status()).isEqualTo(JobStatus.THAT_BAI);
    assertThat(job.progress()).isNull();
    assertThat(outcomes).containsExactly("THU_LAI", "LOI");
  }

  @Test
  void nonCompliantPdfAndTamperedTemplateAreRejected() {
    form("{}");
    form("{}");
    request(UUID.randomUUID());
    pdfaCompliant = false;
    assertThat(service.processOnce(jobs.items.get(0).id())).isEqualTo(RenderJobStatus.LOI);
    assertThat(jobs.items.get(0).error()).contains("PDF/A-2b").contains("6.2.11.4.1");
    pdfaCompliant = true;
    objects.put("templates/mau/1a.docx", "bi-thay".getBytes(StandardCharsets.UTF_8));
    assertThat(service.processOnce(jobs.items.get(1).id())).isEqualTo(RenderJobStatus.LOI);
    assertThat(jobs.items.get(1).error()).contains("không khớp băm");
    assertThat(events).isEmpty();
  }

  @Test
  void requestFailsForUnknownPackageNoFormsOrNoActiveTemplate() {
    packageExists = false;
    assertThatThrownBy(() -> request(UUID.randomUUID()))
        .isInstanceOf(DocgenNotFoundException.class);
    packageExists = true;
    assertThatThrownBy(() -> request(UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(RenderService.NO_FORMS);
    forms.add(new RenderInputPort.FormRef(UUID.randomUUID(), "MAU_1A", "KHOI_B", new byte[32]));
    assertThatThrownBy(() -> request(UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(RenderService.NO_TEMPLATE);
    assertThat(service.job(UUID.randomUUID())).isEmpty();
  }

  @Test
  void summaryReportsPartialProgressAndInvalidJsonIsRejected() {
    UUID bad = UUID.randomUUID();
    byte[] notJson = "khong-phai-json".getBytes(StandardCharsets.UTF_8);
    data.put(bad, notJson);
    forms.add(new RenderInputPort.FormRef(bad, "MAU_1A", null, Hashes.sha256(notJson)));
    form("{}");
    form("{}");
    UUID requestId = UUID.randomUUID();
    request(requestId);
    assertThat(service.processOnce(jobs.items.get(0).id())).isEqualTo(RenderJobStatus.LOI);
    assertThat(jobs.items.get(0).error()).contains("JSON");
    service.processOnce(jobs.items.get(1).id());
    Job job = service.job(requestId).orElseThrow();
    assertThat(job.status()).isEqualTo(JobStatus.DANG_CHAY);
    assertThat(job.progress()).isEqualTo(66);
    assertThat(service.parts(requestId)).hasSize(3);
  }

  @Test
  void abandonedClaimIsTakenOverAfterLeaseAndLateFinishIsIgnored() {
    form("{}");
    UUID requestId = UUID.randomUUID();
    request(requestId);
    UUID jobId = jobs.items.get(0).id();
    duringConvert =
        () -> {
          duringConvert = () -> {};
          // Lượt thứ nhất treo quá hạn thuê; một worker khác nhận lại và sinh xong trước.
          now = T0.plus(Duration.ofMinutes(6));
          assertThat(service.processOnce(jobId)).isEqualTo(RenderJobStatus.XONG);
        };
    assertThat(service.processOnce(jobId)).isNull();
    assertThat(jobs.items.get(0).attempts()).isEqualTo(2);
    assertThat(jobs.outputs).hasSize(1);
    assertThat(events).containsExactly(jobId);
    assertThat(outcomes).containsExactly("XONG", "MAT_LUOT");
  }

  @Test
  void claimStillLeasedIsNotTakenOver() {
    form("{}");
    request(UUID.randomUUID());
    UUID jobId = jobs.items.get(0).id();
    duringConvert =
        () -> {
          duringConvert = () -> {};
          now = T0.plus(Duration.ofMinutes(1));
          assertThat(service.processOnce(jobId)).isNull();
        };
    assertThat(service.processOnce(jobId)).isEqualTo(RenderJobStatus.XONG);
    assertThat(jobs.items.get(0).attempts()).isEqualTo(1);
  }

  @Test
  void pinnedTemplateVersionOfPeriodIsUsedEvenWhenRetired() {
    byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
    UUID oldPeriodForm = UUID.randomUUID();
    data.put(oldPeriodForm, json);
    forms.add(
        new RenderInputPort.FormRef(
            oldPeriodForm, "MAU_1A", "KHOI_A", Hashes.sha256(json), 1, "PP-DANG", "1.0.0"));
    UUID currentForm = form("{}");
    request(UUID.randomUUID());

    RenderJob old = jobs.items.get(0);
    assertThat(old.formInstanceId()).isEqualTo(oldPeriodForm);
    assertThat(old.templateId()).isEqualTo(templates.items.get(0).id());
    assertThat(old.provenance()).isEqualTo(new RenderJob.Provenance(PACKAGE, "PP-DANG", "1.0.0"));
    assertThat(jobs.items.get(1).formInstanceId()).isEqualTo(currentForm);
    assertThat(jobs.items.get(1).templateId()).isEqualTo(templates.items.get(1).id());

    assertThat(service.processOnce(old.id())).isEqualTo(RenderJobStatus.XONG);
    RenderStamp stamp = stamps.get(0);
    assertThat(stamp.templateVersion()).isEqualTo(1);
    assertThat(stamp.packageId()).isEqualTo(PACKAGE);
    assertThat(stamp.inputSha256()).isEqualTo(Hashes.sha256(json));
    assertThat(stamp.properties())
        .containsEntry(RenderStamp.POLICY_PACK, "PP-DANG")
        .containsEntry(RenderStamp.POLICY_PACK_VERSION, "1.0.0")
        .containsEntry(RenderStamp.FORM_CODE, "MAU_1A")
        .containsEntry(RenderStamp.TEMPLATE_VERSION, "1")
        .containsEntry(RenderStamp.PACKAGE_ID, PACKAGE.toString())
        .containsEntry(
            RenderStamp.INPUT_SHA256, java.util.HexFormat.of().formatHex(Hashes.sha256(json)));
    assertThat(stamp.footerText())
        .isEqualTo("Mẫu 1A, phiên bản mẫu 1 · Bộ quy tắc đánh giá PP-DANG phiên bản 1.0.0");
  }

  @Test
  void missingOrDraftPinnedVersionIsRefused() {
    templates.items.add(template(3, TemplateStatus.NHAP, List.of()));
    for (int pinned : new int[] {3, 9}) {
      forms.clear();
      forms.add(
          new RenderInputPort.FormRef(
              UUID.randomUUID(), "MAU_1A", null, new byte[32], pinned, "PP", "1"));
      assertThatThrownBy(() -> request(UUID.randomUUID()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining(RenderService.NO_TEMPLATE);
    }
  }

  @Test
  void stampWithoutProvenanceStillCarriesFormAndTemplate() {
    form("{}");
    request(UUID.randomUUID());
    jobs.items.set(0, jobs.items.get(0).withProvenance(null));
    assertThat(service.processOnce(jobs.items.get(0).id())).isEqualTo(RenderJobStatus.XONG);
    RenderStamp stamp = stamps.get(0);
    assertThat(stamp.properties()).containsEntry(RenderStamp.POLICY_PACK, "");
    assertThat(stamp.footerText()).isEqualTo("Mẫu 1A, phiên bản mẫu 2");
    assertThat(RenderStamp.formLabel("BANG_CHAM_DIEM")).isEqualTo("Bảng chấm điểm");
    assertThat(RenderStamp.formLabel("KHAC")).isEqualTo("KHAC");
    assertThat(new RenderStamp("PP", null, "MAU_2", 1, null, new byte[32]).footerText())
        .isEqualTo("Mẫu 2, phiên bản mẫu 1 · Bộ quy tắc đánh giá PP");
  }

  @Test
  void plainRendererFlattensNestedDataAndNulls() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("a", Map.of("b", 1));
    nested.put("c", List.of("x", "y"));
    nested.put("d", null);
    assertThat(new PlainFieldRenderer().fields(nested))
        .containsEntry("a.b", "1")
        .containsEntry("c.1", "x")
        .containsEntry("c.2", "y")
        .containsEntry("d", "");
    assertThat(new PlainFieldRenderer().supports(7)).isTrue();
  }

  @Test
  void requestEventIdIsStablePerRequestAndForm() {
    UUID r = UUID.randomUUID();
    UUID f = UUID.randomUUID();
    assertThat(RenderService.requestEventId(r, f))
        .isEqualTo(RenderService.requestEventId(r, f))
        .isNotEqualTo(RenderService.requestEventId(r, UUID.randomUUID()));
  }

  static final class MemoryTemplates implements TemplateRepository {
    final List<Template> items = new ArrayList<>();

    @Override
    public Optional<Template> findById(UUID id) {
      return items.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    @Override
    public List<Template> findByFormCode(String formCode) {
      return items.stream().filter(t -> t.formCode().equals(formCode)).toList();
    }

    @Override
    public void insert(Template template, UUID actor) {
      items.add(template);
    }
  }

  static final class MemoryJobs implements RenderJobRepository {
    final List<RenderJob> items = new ArrayList<>();
    final List<RenderOutput> outputs = new ArrayList<>();

    @Override
    public Optional<RenderJob> findById(UUID id) {
      return items.stream().filter(j -> j.id().equals(id)).findFirst();
    }

    @Override
    public Optional<RenderJob> lockForProcessing(UUID id) {
      return findById(id).map(MemoryJobs::copy);
    }

    @Override
    public List<RenderJob> findByRequest(UUID requestId) {
      return items.stream().filter(j -> j.requestId().equals(requestId)).toList();
    }

    @Override
    public boolean insertIfAbsent(RenderJob job) {
      if (items.stream().anyMatch(j -> j.requestEventId().equals(job.requestEventId()))) {
        return false;
      }
      items.add(job);
      return true;
    }

    @Override
    public long update(RenderJob job) {
      RenderJob stored = findById(job.id()).orElseThrow();
      if (stored.rowVersion() != job.rowVersion()) {
        throw new IllegalStateException("xung đột");
      }
      RenderJob next =
          new RenderJob(
                  job.id(),
                  job.tenantId(),
                  job.templateId(),
                  job.requestEventId(),
                  job.requestId(),
                  job.formInstanceId(),
                  job.inputSha256(),
                  job.status(),
                  job.attempts(),
                  job.error(),
                  job.createdBy(),
                  job.createdAt(),
                  job.updatedAt(),
                  job.rowVersion() + 1)
              .withProvenance(job.provenance());
      items.set(items.indexOf(stored), next);
      return next.rowVersion();
    }

    @Override
    public void insertOutput(RenderOutput output) {
      outputs.add(output);
    }

    @Override
    public List<RenderOutput> outputsOf(UUID renderJobId) {
      return outputs.stream().filter(o -> o.renderJobId().equals(renderJobId)).toList();
    }

    private static RenderJob copy(RenderJob j) {
      return new RenderJob(
              j.id(),
              j.tenantId(),
              j.templateId(),
              j.requestEventId(),
              j.requestId(),
              j.formInstanceId(),
              j.inputSha256(),
              j.status(),
              j.attempts(),
              j.error(),
              j.createdBy(),
              j.createdAt(),
              j.updatedAt(),
              j.rowVersion())
          .withProvenance(j.provenance());
    }
  }
}
