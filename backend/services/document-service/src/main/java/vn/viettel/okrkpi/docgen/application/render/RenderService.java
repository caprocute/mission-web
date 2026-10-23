package vn.viettel.okrkpi.docgen.application.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.DocxMerger;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.ObjectStore;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.PdfConverter;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.PdfStamper;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.PdfaValidator;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.TransientFailure;
import vn.viettel.okrkpi.docgen.domain.render.OutputKind;
import vn.viettel.okrkpi.docgen.domain.render.RenderJob;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobRepository;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobStatus;
import vn.viettel.okrkpi.docgen.domain.render.RenderOutput;
import vn.viettel.okrkpi.docgen.domain.render.Template;
import vn.viettel.okrkpi.docgen.domain.render.TemplateRepository;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobIds;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Đường ống sinh biểu mẫu của SVC-11 (DV-11-01, QT-11 bước 10).
 *
 * <ol>
 *   <li>{@link #request}: nhận lệnh sinh cho một gói hồ sơ (REST 202 hoặc saga gọi cùng thao tác),
 *       hỏi MOD-06 danh sách biểu mẫu và băm dữ liệu, tạo mỗi biểu mẫu một tác vụ {@code CHO}.
 *   <li>{@link #processOnce}: worker lấy dữ liệu đã chuẩn hoá, từ chối khi băm lệch {@code
 *       input_sha256}, trộn mẫu DOCX, chuyển PDF/A-2b, kiểm veraPDF, ghi tệp vào bucket {@code
 *       dossier} theo khoá có băm, rồi ghi {@code render_output} và EVT-17 trong cùng một giao
 *       dịch.
 * </ol>
 *
 * <p>DV-11-04: mẫu chọn theo phiên bản mà Policy Pack của kỳ chỉ định (kỳ cũ sinh đúng mẫu cũ), mỗi
 * tệp mang tem phiên bản ở chân trang và trong XMP (BR-11-03), và tem được đóng trước khi kiểm
 * veraPDF nên {@code pdfa_validated} luôn nói về đúng tệp đã lưu và đã phát EVT-17.
 */
public class RenderService {

  /** Loại tác vụ dài trong lược đồ {@code Job} của hợp đồng. */
  public static final String JOB_TYPE = "RENDER";

  /** Tiền tố mã tác vụ của SVC-11: cổng cell định tuyến {@code /api/v1/jobs/doc-…} về đây. */
  public static final String JOB_PREFIX = "doc";

  public static final String TEMPLATES_BUCKET = FormComposer.TEMPLATES_BUCKET;
  public static final String DOSSIER_BUCKET = "dossier";

  static final String NO_TEMPLATE = "DOSSIER-422-VALIDATION";
  static final String NO_FORMS = "DOSSIER-409-INVALID-STATE";

  private final TemplateRepository templates;
  private final RenderJobRepository jobs;
  private final RenderInputPort inputs;
  private final FormComposer composer;
  private final PdfConverter converter;
  private final PdfaValidator validator;
  private final PdfStamper stamper;
  private final ObjectStore store;
  private final RenderEvents events;
  private final RenderMetrics metrics;
  private final Clock clock;
  private final int maxAttempts;
  private final Duration lease;
  private final TransactionOperations tx;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public RenderService(
      TemplateRepository templates,
      RenderJobRepository jobs,
      RenderInputPort inputs,
      List<FormRenderer> renderers,
      DocxMerger merger,
      PdfConverter converter,
      PdfaValidator validator,
      PdfStamper stamper,
      ObjectStore store,
      RenderEvents events,
      RenderMetrics metrics,
      ObjectMapper json,
      Clock clock,
      int maxAttempts,
      Duration lease,
      TransactionOperations tx) {
    this(
        templates,
        jobs,
        inputs,
        renderers,
        merger,
        converter,
        validator,
        stamper,
        store,
        events,
        metrics,
        json,
        clock,
        maxAttempts,
        lease,
        tx,
        SealedValueOpener.UNAVAILABLE);
  }

  /** Như trên, kèm bộ giải mã giá trị C2 của dữ liệu biểu mẫu (DV-11-02). */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public RenderService(
      TemplateRepository templates,
      RenderJobRepository jobs,
      RenderInputPort inputs,
      List<FormRenderer> renderers,
      DocxMerger merger,
      PdfConverter converter,
      PdfaValidator validator,
      PdfStamper stamper,
      ObjectStore store,
      RenderEvents events,
      RenderMetrics metrics,
      ObjectMapper json,
      Clock clock,
      int maxAttempts,
      Duration lease,
      TransactionOperations tx,
      SealedValueOpener opener) {
    this.templates = templates;
    this.jobs = jobs;
    this.inputs = inputs;
    this.composer = new FormComposer(store, renderers, merger, json, new FormModelBuilder(opener));
    this.converter = converter;
    this.validator = validator;
    this.stamper = stamper;
    this.store = store;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
    this.lease = lease;
    this.tx = tx;
  }

  /**
   * Lệnh sinh của một gói hồ sơ.
   *
   * @param requestId mã lệnh sinh, cũng là mã tác vụ dài trả trong {@code Location}
   */
  public record RequestCommand(
      UUID tenantId, UUID caller, UUID packageId, List<String> formCodes, UUID requestId) {
    public RequestCommand {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(caller, "caller");
      Objects.requireNonNull(packageId, "packageId");
      Objects.requireNonNull(requestId, "requestId");
      formCodes = formCodes == null ? List.of() : List.copyOf(formCodes);
    }
  }

  /** Tạo tác vụ sinh cho từng biểu mẫu của gói; lệnh gửi lại không tạo thêm tác vụ. */
  @Transactional
  public Job request(RequestCommand command) {
    List<RenderInputPort.FormRef> forms =
        inputs
            .forms(command.tenantId(), command.caller(), command.packageId(), command.formCodes())
            .orElseThrow(() -> new DocgenNotFoundException("Không tìm thấy gói hồ sơ"));
    if (forms.isEmpty()) {
      throw new BusinessException(NO_FORMS, "Gói hồ sơ chưa có biểu mẫu nào cần sinh.");
    }
    Instant now = clock.instant();
    for (RenderInputPort.FormRef form : forms) {
      Template template = template(form);
      jobs.insertIfAbsent(
          RenderJob.create(
                  Ids.newUuidV7(),
                  command.tenantId(),
                  template.id(),
                  requestEventId(command.requestId(), form.formInstanceId()),
                  command.requestId(),
                  form.formInstanceId(),
                  form.dataSnapshotHash(),
                  command.caller(),
                  now)
              .withProvenance(
                  new RenderJob.Provenance(
                      command.packageId(), form.policyPackCode(), form.policyPackVersion()))
              .withVariant(RenderJob.Variant.reopened(form.reopenRequestId())));
    }
    return job(command.requestId()).orElseThrow();
  }

  /**
   * Lệnh sinh bản nháp ngoài gói hồ sơ (DV-11-03, SCR-0904 "Xuất Mẫu 3").
   *
   * @param profileIds hồ sơ đang chọn, tối đa theo hợp đồng
   */
  public record DraftCommand(
      UUID tenantId,
      UUID caller,
      String formCode,
      UUID periodId,
      List<UUID> profileIds,
      UUID requestId) {
    public DraftCommand {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(caller, "caller");
      Objects.requireNonNull(formCode, "formCode");
      Objects.requireNonNull(periodId, "periodId");
      Objects.requireNonNull(requestId, "requestId");
      profileIds = profileIds == null ? List.of() : List.copyOf(profileIds);
    }
  }

  /**
   * Tạo tác vụ sinh bản nháp: MOD-06 tạo biểu mẫu không gắn gói, mỗi biểu mẫu một tác vụ đánh dấu
   * nháp; tệp mang dấu "Bản nháp" và không có tem gói.
   */
  @Transactional
  public Job requestDrafts(DraftCommand command) {
    List<RenderInputPort.FormRef> forms =
        inputs
            .draftForms(
                command.tenantId(),
                command.caller(),
                command.formCode(),
                command.periodId(),
                command.profileIds())
            .orElseThrow(() -> new DocgenNotFoundException("Không tìm thấy kỳ hoặc hồ sơ"));
    if (forms.isEmpty()) {
      throw new BusinessException(NO_FORMS, "Không có hồ sơ nào để sinh biểu mẫu nháp.");
    }
    Instant now = clock.instant();
    for (RenderInputPort.FormRef form : forms) {
      Template template = template(form);
      jobs.insertIfAbsent(
          RenderJob.create(
                  Ids.newUuidV7(),
                  command.tenantId(),
                  template.id(),
                  requestEventId(command.requestId(), form.formInstanceId()),
                  command.requestId(),
                  form.formInstanceId(),
                  form.dataSnapshotHash(),
                  command.caller(),
                  now)
              .withProvenance(
                  new RenderJob.Provenance(null, form.policyPackCode(), form.policyPackVersion()))
              .withVariant(RenderJob.Variant.DRAFT));
    }
    return job(command.requestId()).orElseThrow();
  }

  /**
   * Mã sự kiện yêu cầu của một biểu mẫu trong một lệnh sinh: cố định theo cặp (lệnh, biểu mẫu) để
   * {@code UQ request_event_id} chặn tác vụ trùng khi lệnh được gửi lại (suy luận).
   */
  static UUID requestEventId(UUID requestId, UUID formInstanceId) {
    ByteBuffer buffer = ByteBuffer.allocate(32);
    buffer.putLong(requestId.getMostSignificantBits()).putLong(requestId.getLeastSignificantBits());
    buffer
        .putLong(formInstanceId.getMostSignificantBits())
        .putLong(formInstanceId.getLeastSignificantBits());
    return UUID.nameUUIDFromBytes(buffer.array());
  }

  /**
   * Mẫu của một biểu mẫu: đúng phiên bản Policy Pack của kỳ chỉ định khi có (kể cả phiên bản đã
   * ngừng, để kỳ cũ sinh lại đúng mẫu cũ), ngược lại phiên bản đang hiệu lực mới nhất.
   */
  private Template template(RenderInputPort.FormRef form) {
    return templateFor(form.formCode(), form.subjectGroupCode(), form.templateVersion());
  }

  private Template activeTemplate(String formCode, String subjectGroupCode) {
    return templates.findByFormCode(formCode).stream()
        .filter(t -> t.appliesTo(subjectGroupCode))
        .max(Comparator.comparingInt(Template::versionNo))
        .orElseThrow(
            () ->
                new BusinessException(
                    NO_TEMPLATE, "Chưa có mẫu đang hiệu lực cho biểu mẫu " + formCode + "."));
  }

  /**
   * Sinh một tác vụ đang chờ qua ba bước để không giữ giao dịch CSDL trong lúc gọi Gotenberg: nhận
   * việc (giao dịch ngắn, {@code CHO} → {@code DANG_SINH}), sinh tệp và ghi kho ngoài giao dịch,
   * rồi ghi kết quả cùng EVT-17 trong giao dịch thứ hai theo khoá lạc quan. Tác vụ {@code
   * DANG_SINH} quá thời hạn thuê (worker dừng giữa chừng) được nhận lại như một lần thử mới.
   *
   * @return trạng thái sau khi xử lý; {@code null} khi tác vụ không còn chờ, worker khác đang giữ
   *     hoặc lượt này đã mất quyền thuê
   */
  public RenderJobStatus processOnce(UUID jobId) {
    Claim claim = tx.execute(s -> claim(jobId));
    if (claim == null) {
      return null;
    }
    long started = System.nanoTime();
    Outcome outcome;
    try {
      outcome = Outcome.success(render(claim.job(), claim.template()));
    } catch (RejectedDocument e) {
      outcome = Outcome.failure(e.getMessage(), false);
    } catch (TransientFailure e) {
      outcome = Outcome.failure(e.getMessage(), true);
    }
    Outcome result = outcome;
    RenderJobStatus status = tx.execute(s -> finish(claim, result));
    String label =
        status == RenderJobStatus.CHO ? "THU_LAI" : status == null ? "MAT_LUOT" : status.name();
    metrics.rendered(
        claim.template().formCode(), label, Duration.ofNanos(System.nanoTime() - started));
    return status;
  }

  private Claim claim(UUID jobId) {
    Optional<RenderJob> locked = jobs.lockForProcessing(jobId);
    if (locked.isEmpty()) {
      return null;
    }
    RenderJob job = locked.get();
    Instant now = clock.instant();
    if (job.status() == RenderJobStatus.DANG_SINH && job.updatedAt().isBefore(now.minus(lease))) {
      if (!job.retryLater("Lượt sinh trước bị gián đoạn.", maxAttempts, now)) {
        jobs.update(job);
        return null;
      }
    }
    if (job.status() != RenderJobStatus.CHO) {
      return null;
    }
    job.start(now);
    jobs.update(job);
    RenderJob claimed = jobs.findById(jobId).orElseThrow();
    return new Claim(claimed, templates.findById(claimed.templateId()).orElseThrow());
  }

  private RenderJobStatus finish(Claim claim, Outcome outcome) {
    RenderJob job = jobs.findById(claim.job().id()).orElseThrow();
    if (job.rowVersion() != claim.job().rowVersion() || job.status() != RenderJobStatus.DANG_SINH) {
      return null;
    }
    Instant now = clock.instant();
    if (outcome.rendered() != null) {
      Rendered r = outcome.rendered();
      RenderOutput output =
          new RenderOutput(
              Ids.newUuidV7(),
              job.tenantId(),
              job.id(),
              OutputKind.PDFA,
              r.key(),
              r.sha256(),
              r.size(),
              true);
      jobs.insertOutput(output);
      job.complete(now);
      long version = jobs.update(job);
      events.formGenerated(job, claim.template(), output, version);
    } else if (outcome.retryable()) {
      job.retryLater(outcome.reason(), maxAttempts, now);
      jobs.update(job);
    } else {
      job.fail(outcome.reason(), now);
      jobs.update(job);
    }
    return job.status();
  }

  private Rendered render(RenderJob job, Template template) {
    RenderInputPort.FormData data =
        inputs
            .data(job.tenantId(), job.formInstanceId())
            .orElseThrow(() -> new RejectedDocument("Không còn dữ liệu của biểu mẫu nguồn."));
    if (!job.inputMatches(Hashes.sha256(data.canonicalJson()))) {
      throw new RejectedDocument(
          "Dữ liệu biểu mẫu đã thay đổi sau khi yêu cầu sinh (băm dữ liệu không khớp).");
    }
    RenderStamp stamp = stamp(job, template);
    byte[] merged =
        composer.compose(
            template, data.canonicalJson(), context(job, template), stamp.footerText());
    byte[] pdf = stamper.stamp(converter.toPdfA2b(merged, template.formCode() + ".docx"), stamp);
    PdfaValidator.Result check = validator.validate(pdf);
    if (!check.compliant()) {
      throw new RejectedDocument("Tệp sinh ra không đạt chuẩn PDF/A-2b: " + check.summary());
    }
    byte[] sha = Hashes.sha256(pdf);
    String key = objectKey(job, template, sha);
    store.put(DOSSIER_BUCKET, key, pdf, "application/pdf");
    return new Rendered(key, sha, pdf.length);
  }

  static RenderContext context(RenderJob job, Template template) {
    RenderJob.Provenance p = job.provenance();
    return new RenderContext(
        job.tenantId(),
        job.formInstanceId(),
        template.formCode(),
        template.versionNo(),
        p.policyPackCode(),
        p.policyPackVersion(),
        job.variant().draft());
  }

  static RenderStamp stamp(RenderJob job, Template template) {
    RenderJob.Provenance p = job.provenance();
    return new RenderStamp(
        p.policyPackCode(),
        p.policyPackVersion(),
        template.formCode(),
        template.versionNo(),
        p.packageId(),
        job.inputSha256(),
        job.variant().reopenRequestId(),
        job.variant().draft());
  }

  /** Tác vụ đã nhận cùng mẫu của nó. */
  private record Claim(RenderJob job, Template template) {}

  /** Tệp đã ghi vào kho. */
  private record Rendered(String key, byte[] sha256, long size) {}

  /** Kết quả lượt sinh: có tệp, hoặc lý do lỗi kèm cờ thử lại. */
  private record Outcome(Rendered rendered, String reason, boolean retryable) {
    static Outcome success(Rendered rendered) {
      return new Outcome(rendered, null, false);
    }

    static Outcome failure(String reason, boolean retryable) {
      return new Outcome(null, reason, retryable);
    }
  }

  /** Khoá tệp có băm: tệp không bị ghi đè và đường dẫn tự chứng minh nội dung. */
  static String objectKey(RenderJob job, Template template, byte[] sha) {
    return job.tenantId()
        + "/forms/"
        + template.formCode()
        + "/"
        + job.formInstanceId()
        + "/"
        + HexFormat.of().formatHex(sha)
        + ".pdf";
  }

  /** Bước trộn dùng chung với bản xem trước. */
  FormComposer composer() {
    return composer;
  }

  /** Mẫu của một biểu mẫu theo phiên bản kỳ ghim (dùng chung với bản xem trước). */
  Template templateFor(String formCode, String subjectGroupCode, Integer templateVersion) {
    if (templateVersion == null) {
      return activeTemplate(formCode, subjectGroupCode);
    }
    int pinned = templateVersion;
    return templates.findByFormCode(formCode).stream()
        .filter(t -> t.pinnedFor(pinned, subjectGroupCode))
        .findFirst()
        .orElseThrow(
            () ->
                new BusinessException(
                    NO_TEMPLATE,
                    "Chưa có mẫu biểu mẫu "
                        + formCode
                        + " phiên bản "
                        + pinned
                        + " theo bộ quy tắc của kỳ."));
  }

  /** Trạng thái lệnh sinh theo lược đồ {@code Job}; rỗng khi lệnh không thuộc tenant hiện tại. */
  @Transactional(readOnly = true)
  public Optional<Job> job(UUID requestId) {
    List<RenderJob> parts = jobs.findByRequest(requestId);
    if (parts.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(summarize(requestId, parts));
  }

  /** Người tạo lệnh sinh (chỉ người này xem được tác vụ, theo {@code jobs.yaml}). */
  @Transactional(readOnly = true)
  public Optional<UUID> initiator(UUID requestId) {
    return jobs.findByRequest(requestId).stream().findFirst().map(RenderJob::createdBy);
  }

  static Job summarize(UUID requestId, List<RenderJob> parts) {
    long done = parts.stream().filter(p -> p.status() == RenderJobStatus.XONG).count();
    long failed = parts.stream().filter(p -> p.status() == RenderJobStatus.LOI).count();
    boolean waiting = parts.stream().allMatch(p -> p.status() == RenderJobStatus.CHO);
    JobStatus status;
    String message;
    if (done == parts.size()) {
      status = JobStatus.HOAN_TAT;
      message = "Đã sinh xong " + done + " biểu mẫu.";
    } else if (done + failed == parts.size()) {
      status = JobStatus.THAT_BAI;
      message = "Có " + failed + "/" + parts.size() + " biểu mẫu không sinh được.";
    } else if (waiting) {
      status = JobStatus.CHO;
      message = null;
    } else {
      status = JobStatus.DANG_CHAY;
      message = "Đã sinh " + done + "/" + parts.size() + " biểu mẫu.";
    }
    Instant updated =
        parts.stream().map(RenderJob::updatedAt).max(Comparator.naturalOrder()).orElseThrow();
    int progress = (int) ((done + failed) * 100 / parts.size());
    return new Job(
        JobIds.format(JOB_PREFIX, requestId),
        JOB_TYPE,
        status,
        status == JobStatus.THAT_BAI ? null : progress,
        message,
        resultLocation(status, parts),
        null,
        updated.atOffset(ZoneOffset.UTC));
  }

  /** Đường dẫn biểu mẫu nháp đã sinh, theo hợp đồng {@code getDossierForm}. */
  public static final String FORM_LOCATION = "/api/v1/dossiers/forms/";

  /**
   * Kết quả của lệnh sinh nháp đã xong một biểu mẫu là mã biểu mẫu ({@code
   * createDossierDraftForms}); lệnh nhiều biểu mẫu hay lệnh sinh cho gói không có đường dẫn kết quả
   * riêng (suy luận).
   */
  static String resultLocation(JobStatus status, List<RenderJob> parts) {
    if (status != JobStatus.HOAN_TAT || parts.size() != 1 || !parts.get(0).variant().draft()) {
      return null;
    }
    return FORM_LOCATION + parts.get(0).formInstanceId();
  }

  /** Các tác vụ của một lệnh sinh, để tra cứu và kiểm thử. */
  @Transactional(readOnly = true)
  public List<RenderJob> parts(UUID requestId) {
    return new ArrayList<>(jobs.findByRequest(requestId));
  }
}
