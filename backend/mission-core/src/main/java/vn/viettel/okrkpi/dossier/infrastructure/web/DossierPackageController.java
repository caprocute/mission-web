package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.dossiers.model.DocgenRenderStatus;
import vn.viettel.okrkpi.contract.dossiers.model.DossierChecklistItem;
import vn.viettel.okrkpi.contract.dossiers.model.DossierForm;
import vn.viettel.okrkpi.contract.dossiers.model.DossierFormCode;
import vn.viettel.okrkpi.contract.dossiers.model.DossierFormPage;
import vn.viettel.okrkpi.contract.dossiers.model.DossierFormStatus;
import vn.viettel.okrkpi.contract.dossiers.model.DossierPackage;
import vn.viettel.okrkpi.contract.dossiers.model.DossierPackageChecklist;
import vn.viettel.okrkpi.contract.dossiers.model.DossierPackageCreate;
import vn.viettel.okrkpi.contract.dossiers.model.DossierPackageFormCounts;
import vn.viettel.okrkpi.contract.dossiers.model.DossierPackageKind;
import vn.viettel.okrkpi.contract.dossiers.model.DossierPackagePage;
import vn.viettel.okrkpi.contract.dossiers.model.DossierPackageSignRequest;
import vn.viettel.okrkpi.contract.dossiers.model.DossierPackageStatus;
import vn.viettel.okrkpi.contract.dossiers.model.DossierRegenerateRequest;
import vn.viettel.okrkpi.contract.dossiers.model.DossierSignJob;
import vn.viettel.okrkpi.contract.dossiers.model.DossierTransferDestination;
import vn.viettel.okrkpi.contract.dossiers.model.DossierTransferRequest;
import vn.viettel.okrkpi.contract.dossiers.model.PeriodRef;
import vn.viettel.okrkpi.contract.dossiers.model.Ref;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierExportService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService.NewPackageCommand;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService.PackageView;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierSigningService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierTransferService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.Filter;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.Checklist;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.RenderProgress;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Gói hồ sơ số của {@code dossiers.yaml} (CORE-06-06, QT-11 bước 10–12, SCR-1102): {@code
 * listDossierPackages}, {@code createDossierPackage}, {@code getDossierPackage}, {@code
 * listDossierPackageForms}, {@code regenerateDossierPackageForms} (202); CORE-06-07 thêm {@code
 * getDossierPackageChecklist} và {@code signDossierPackage} (202 kèm mã yêu cầu ký); CORE-06-08
 * thêm {@code exportDossierPackage} và {@code createDossierPackageTransfer} (202). Phạm vi đơn vị
 * của phân công đang chọn; tên đơn vị, người chưa có bản sao trong MOD-06 nên trả rỗng.
 */
@RestController
@RequestMapping("/api/v1/dossiers/packages")
public class DossierPackageController {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 200;
  static final String FORMAT = "PDF/A-2b";

  static final String SIGN_TYPE = "ESIGN";

  private final DossierPackageService service;
  private final DossierSigningService signing;
  private final DossierExportService exports;
  private final DossierTransferService transfers;
  private final DossierCallers callers;
  private final ObjectProvider<PeriodApi> periods;

  public DossierPackageController(
      DossierPackageService service,
      DossierSigningService signing,
      DossierExportService exports,
      DossierTransferService transfers,
      ObjectProvider<DossierCaller> callers,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodApi> periods) {
    this.service = Objects.requireNonNull(service, "service");
    this.signing = Objects.requireNonNull(signing, "signing");
    this.exports = Objects.requireNonNull(exports, "exports");
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.callers = new DossierCallers(callers, subjects);
    this.periods = Objects.requireNonNull(periods, "periods");
  }

  @GetMapping
  @RequiresPermission(
      operationId = "listDossierPackages",
      module = "dossiers",
      resourceType = "dossier_package")
  public ResponseEntity<DossierPackagePage> listDossierPackages(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    int size = size(limit);
    callers.actor();
    Filter filter =
        new Filter(
            period,
            unit,
            parse(PackageKind.class, kind, "kind", "Loại gói không hợp lệ."),
            parse(PackageStatus.class, status, "status", "Trạng thái gói không hợp lệ."));
    List<PackageView> rows =
        service.list(
            tenant,
            callers.unit().orElse(null),
            filter,
            DossierMeetingController.decodeCursor(cursor),
            size + 1);
    boolean more = rows.size() > size;
    List<PackageView> page = more ? rows.subList(0, size) : rows;
    Map<UUID, PeriodRef> refs = new LinkedHashMap<>();
    List<DossierPackage> items = new ArrayList<>();
    for (PackageView v : page) {
      items.add(dto(v, refs.computeIfAbsent(v.row().periodId(), id -> periodRef(tenant, id))));
    }
    String next =
        more ? DossierMeetingController.encodeCursor(page.get(page.size() - 1).row().id()) : null;
    return ResponseEntity.ok(new DossierPackagePage(items, next));
  }

  @PostMapping
  @Idempotent
  @RequiresPermission(
      operationId = "createDossierPackage",
      module = "dossiers",
      resourceType = "dossier_package")
  public ResponseEntity<DossierPackage> createDossierPackage(
      @RequestBody DossierPackageCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.actor();
    List<ProblemFieldError> errors = new ArrayList<>();
    if (body.getPeriodId() == null) {
      errors.add(new ProblemFieldError("periodId", "Chưa chọn kỳ đánh giá.", null));
    }
    if (body.getOrgUnitId() == null) {
      errors.add(new ProblemFieldError("orgUnitId", "Chưa chọn đơn vị.", null));
    }
    if (body.getPackageKind() == null) {
      errors.add(new ProblemFieldError("packageKind", "Chưa chọn loại gói.", null));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "DOSSIER-422-VALIDATION", "Thông tin gói hồ sơ chưa hợp lệ.", errors);
    }
    PackageView view =
        service.create(
            tenant,
            actor,
            callers.unit().orElse(null),
            new NewPackageCommand(
                body.getPeriodId(),
                body.getOrgUnitId(),
                PackageKind.valueOf(body.getPackageKind().getValue()),
                body.getDeadline()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/dossiers/packages/" + view.row().id()))
        .eTag(ETags.of(view.row().rowVersion()))
        .body(dto(view, periodRef(tenant, view.row().periodId())));
  }

  @GetMapping("/{id}")
  @RequiresPermission(
      operationId = "getDossierPackage",
      module = "dossiers",
      resourceType = "dossier_package")
  public ResponseEntity<DossierPackage> getDossierPackage(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    callers.actor();
    PackageView view =
        service.get(tenant, callers.unit().orElse(null), id).orElseThrow(this::notFound);
    return ETags.ok(dto(view, periodRef(tenant, view.row().periodId())), view.row().rowVersion());
  }

  @GetMapping("/{id}/forms")
  @RequiresPermission(
      operationId = "listDossierPackageForms",
      module = "dossiers",
      resourceType = "form_instance")
  public ResponseEntity<DossierFormPage> listDossierPackageForms(
      @PathVariable("id") UUID id,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "formCode", required = false) String formCode,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    int size = size(limit);
    callers.actor();
    FormStatus filter =
        parse(FormStatus.class, status, "status", "Trạng thái biểu mẫu không hợp lệ.");
    if (formCode != null) {
      try {
        DossierFormCode.fromValue(formCode);
      } catch (IllegalArgumentException e) {
        throw invalid("formCode", "Mã biểu mẫu không hợp lệ.");
      }
    }
    List<FormRow> rows;
    try {
      rows =
          service.forms(
              tenant,
              callers.unit().orElse(null),
              id,
              filter,
              formCode,
              DossierMeetingController.decodeCursor(cursor),
              size + 1);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    boolean more = rows.size() > size;
    List<FormRow> page = more ? rows.subList(0, size) : rows;
    List<DossierForm> items = page.stream().map(DossierPackageController::form).toList();
    String next =
        more ? DossierMeetingController.encodeCursor(page.get(page.size() - 1).id()) : null;
    return ResponseEntity.ok(new DossierFormPage(items, next));
  }

  @PostMapping("/{id}/regenerate")
  @Idempotent
  @RequiresPermission(
      operationId = "regenerateDossierPackageForms",
      module = "dossiers",
      resourceType = "dossier_package")
  public ResponseEntity<Job> regenerateDossierPackageForms(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) DossierRegenerateRequest body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "DOSSIER");
    UUID actor = callers.actor();
    List<UUID> formIds = body == null || body.getFormIds() == null ? List.of() : body.getFormIds();
    if (formIds.size() > MAX_LIMIT) {
      throw invalid("formIds", "Mỗi lần sinh lại tối đa 200 biểu mẫu.");
    }
    PackageView view;
    try {
      view = service.regenerate(tenant, actor, callers.unit().orElse(null), id, expected, formIds);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return Jobs.accepted(PackageRenderJobs.job(view));
  }

  @GetMapping("/{id}/checklist")
  @RequiresPermission(
      operationId = "getDossierPackageChecklist",
      module = "dossiers",
      resourceType = "dossier_package")
  public ResponseEntity<DossierPackageChecklist> getDossierPackageChecklist(
      @PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    callers.actor();
    Checklist checklist;
    try {
      checklist = signing.checklist(tenant, callers.unit().orElse(null), id);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    List<DossierChecklistItem> items = new ArrayList<>();
    for (Checklist.Item i : checklist.items()) {
      DossierChecklistItem item =
          new DossierChecklistItem(
              i.code(),
              i.label(),
              i.ok()
                  ? DossierChecklistItem.StatusEnum.DAT
                  : DossierChecklistItem.StatusEnum.CANH_BAO);
      if (i.count() > 0) {
        item.setCount(i.count());
      }
      items.add(item);
    }
    DossierPackageChecklist body = new DossierPackageChecklist(checklist.passed(), items);
    body.setEvaluatedAt(time(checklist.evaluatedAt()));
    return ResponseEntity.ok(body);
  }

  @PostMapping("/{id}/sign")
  @Idempotent
  @RequiresPermission(
      operationId = "signDossierPackage",
      module = "dossiers",
      resourceType = "dossier_package")
  public ResponseEntity<DossierSignJob> signDossierPackage(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) DossierPackageSignRequest body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "DOSSIER");
    UUID actor = callers.actor();
    String provider =
        body == null || body.getProvider() == null ? null : body.getProvider().getValue();
    DossierSigningService.SignStarted started;
    try {
      started =
          signing.sign(
              tenant,
              actor,
              callers.unit().orElse(null),
              id,
              expected,
              provider,
              idempotencyKey,
              authorization);
    } catch (NoSuchElementException e) {
      throw notFound();
    } catch (DossierSigningService.SigningUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
    UUID request = started.signRequestId();
    DossierSignJob job =
        new DossierSignJob(
            request.toString(),
            SIGN_TYPE,
            vn.viettel.okrkpi.contract.dossiers.model.JobStatus.DANG_CHAY,
            request);
    job.setProgress(0);
    job.setMessage(PackageSignJobs.WAITING);
    job.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(Jobs.location(request.toString()))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(Jobs.DEFAULT_RETRY_AFTER_SECONDS))
        .body(job);
  }

  @PostMapping("/{id}/export")
  @Idempotent
  @RequiresPermission(
      operationId = "exportDossierPackage",
      module = "dossiers",
      resourceType = "dossier_package")
  public ResponseEntity<Job> exportDossierPackage(@PathVariable("id") UUID id) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.actor();
    DossierExportService.ExportJob job;
    try {
      job = exports.request(tenant, actor, callers.unit().orElse(null), id);
    } catch (NoSuchElementException e) {
      throw notFound();
    }
    return Jobs.accepted(PackageExportJobs.job(job));
  }

  @PostMapping("/{id}/transfers")
  @Idempotent
  @RequiresPermission(
      operationId = "createDossierPackageTransfer",
      module = "dossiers",
      resourceType = "dossier_package")
  public ResponseEntity<Job> createDossierPackageTransfer(
      @PathVariable("id") UUID id, @RequestBody(required = false) DossierTransferRequest body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.actor();
    if (body == null || body.getDestination() == null) {
      throw invalid("destination", "Chưa chọn nơi nhận hồ sơ.");
    }
    List<UUID> profileIds =
        body.getProfileIds() == null ? List.of() : List.copyOf(body.getProfileIds());
    if (profileIds.size() > MAX_LIMIT) {
      throw invalid("profileIds", "Mỗi lần gửi tối đa 200 hồ sơ.");
    }
    DossierTransferService.TransferJob job;
    try {
      job =
          transfers.request(
              tenant,
              actor,
              callers.unit().orElse(null),
              id,
              body.getDestination().getValue(),
              profileIds);
    } catch (NoSuchElementException e) {
      throw notFound();
    } catch (DossierTransferService.TransferUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
    return Jobs.accepted(PackageTransferJobs.job(job));
  }

  // ---------------------------------------------------------------- ánh xạ

  static DossierPackage dto(PackageView v, PeriodRef period) {
    PackageRow r = v.row();
    RenderProgress p = v.progress();
    DossierPackageFormCounts counts =
        new DossierPackageFormCounts(p.total(), p.rendered(), p.failed());
    counts.setSigned(p.signed());
    DossierPackage dto =
        new DossierPackage(
            r.id(),
            r.code(),
            DossierPackageKind.fromValue(r.kind().name()),
            period,
            DossierPackageStatus.fromValue(r.status().name()),
            counts,
            (int) r.rowVersion());
    dto.setOrgUnit(new Ref(r.orgUnitId(), ""));
    dto.setPerson(r.personId() == null ? null : new Ref(r.personId(), ""));
    dto.setDeadline(r.deadline());
    dto.setSubmittedAt(time(r.submittedAt()));
    dto.setVersionNo(r.versionNo());
    dto.setProfileCount(v.profileCount());
    dto.setManifestHash(hex(r.manifestHash()));
    dto.setPackageSha256(hex(r.packageSha256()));
    dto.setTransferredTo(
        r.transferredTo() == null ? null : DossierTransferDestination.fromValue(r.transferredTo()));
    dto.setSignRequestId(r.signRequestId());
    return dto;
  }

  static DossierForm form(FormRow f) {
    DossierForm dto =
        new DossierForm(
            f.id(),
            DossierFormCode.fromValue(f.formCode()),
            f.profileId(),
            f.templateVersion() == null ? "" : f.templateVersion(),
            DossierFormStatus.fromValue(f.status().name()));
    dto.setPackageId(f.packageId());
    dto.setRenderStatus(renderStatus(f));
    dto.setFormat(FORMAT);
    dto.setDraft(false);
    dto.setDataSnapshotHash(hex(f.dataSnapshotHash()));
    dto.setPdfSha256(hex(f.pdfSha256()));
    dto.setSignedAt(time(f.signedAt()));
    return dto;
  }

  /** Trạng thái tác vụ sinh suy ra từ biểu mẫu: đã gửi lệnh mà chưa có tệp là đang sinh. */
  static DocgenRenderStatus renderStatus(FormRow f) {
    return switch (f.status()) {
      case DA_SINH, DA_KY -> DocgenRenderStatus.XONG;
      case LOI -> DocgenRenderStatus.LOI;
      case CHO_SINH ->
          f.renderJobId() == null ? DocgenRenderStatus.CHO : DocgenRenderStatus.DANG_SINH;
    };
  }

  private PeriodRef periodRef(TenantId tenant, UUID periodId) {
    PeriodApi api = periods.getIfAvailable();
    Optional<PeriodSnapshot> snapshot =
        api == null ? Optional.empty() : api.findById(tenant, PeriodId.of(periodId));
    return snapshot
        .map(s -> new PeriodRef(periodId, s.name(), s.startDate(), s.endDate()))
        .orElseGet(() -> new PeriodRef(periodId, "", null, null));
  }

  private static <E extends Enum<E>> E parse(
      Class<E> type, String value, String field, String message) {
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw invalid(field, message);
    }
  }

  private static int size(Integer limit) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    return size;
  }

  private static String hex(byte[] value) {
    return value == null ? null : HexFormat.of().formatHex(value);
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atZone(ReviewMeetingService.ZONE).toOffsetDateTime();
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
