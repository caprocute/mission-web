package vn.viettel.okrkpi.org.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.WebUtils;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.ChangeResult;
import vn.viettel.okrkpi.org.application.MyContextService;
import vn.viettel.okrkpi.org.application.OrgUnitCommandService;
import vn.viettel.okrkpi.org.application.OrgUnitHistoryItem;
import vn.viettel.okrkpi.org.application.OrgUnitQueryService;
import vn.viettel.okrkpi.org.application.OrgUnitView;
import vn.viettel.okrkpi.org.application.Page;
import vn.viettel.okrkpi.org.application.UnitRef;
import vn.viettel.okrkpi.org.application.port.AssignmentStore.PersonSummaryRow;
import vn.viettel.okrkpi.org.application.port.AssignmentStore.UnitAssignmentRow;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.infrastructure.security.ActorFilter;
import vn.viettel.okrkpi.org.infrastructure.web.api.OrgApi;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgPersons200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgUnitAssignments200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgUnitHistory200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgUnits200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListPersonAssignments200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListSubjectGroups200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.MyOrgProfile;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgChangeOrder;
import vn.viettel.okrkpi.org.infrastructure.web.model.SetMyContextRequest;
import vn.viettel.okrkpi.org.infrastructure.web.model.WorkContext;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgChangeRequest;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgTree;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgTreeKind;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnit;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitInput;
import vn.viettel.okrkpi.org.infrastructure.web.model.SearchOrgUnits200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.AssignOrgDiscrepancyRequest;
import vn.viettel.okrkpi.org.infrastructure.web.model.DiscrepancySeverity;
import vn.viettel.okrkpi.org.infrastructure.web.model.ImportOrgExcel202Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgDiscrepancies200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgSyncRuns200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgSyncSchedules200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.MapOrgDiscrepancyRequest;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgDiscrepancy;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgDiscrepancyField;
import vn.viettel.okrkpi.org.infrastructure.web.model.ResolveOrgDiscrepancies200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ResolveOrgDiscrepanciesRequest;
import vn.viettel.okrkpi.org.infrastructure.web.model.StartOrgSyncRunRequest;
import vn.viettel.okrkpi.org.infrastructure.web.model.SyncSource;
import vn.viettel.okrkpi.org.application.sync.DiscrepancyService;
import vn.viettel.okrkpi.org.application.sync.SyncRunService;
import vn.viettel.okrkpi.org.application.port.SyncRunStore.Run;
import vn.viettel.okrkpi.org.application.port.SyncScheduleStore.ScheduleSet;
import java.io.UncheckedIOException;
import java.net.URI;
import vn.viettel.okrkpi.org.application.orgimport.OrgImportService;
import vn.viettel.okrkpi.org.application.e2ee.PublicKeyDirectoryService;
import vn.viettel.okrkpi.org.application.e2ee.RecoveryKeyService;
import vn.viettel.okrkpi.org.application.port.RecoveryKeyStore.StoredRecovery;
import vn.viettel.okrkpi.org.application.port.RecoveryKeyStore.StoredRecoveryKey;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListE2eeDirectoryKeys200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListE2eeRecoveries200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListPersonPublicKeys200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.PersonPublicKey;
import vn.viettel.okrkpi.org.infrastructure.web.model.PublicKeyRegistration;
import vn.viettel.okrkpi.org.infrastructure.web.model.PublicKeyRevocation;
import vn.viettel.okrkpi.org.infrastructure.web.model.RecoveryKey;
import vn.viettel.okrkpi.org.infrastructure.web.model.RecoveryKeyInput;
import vn.viettel.okrkpi.org.infrastructure.web.model.RecoveryRecord;
import vn.viettel.okrkpi.org.infrastructure.web.model.RecoveryRecordInput;

/**
 * Hiện thực giao diện {@link OrgApi} sinh từ {@code contracts/openapi/modules/org.yaml} (ADR-32, interfaceOnly) cho
 * các thao tác cây tổ chức của QT-01, SCR-0103. Thao tác chưa thuộc phạm vi giữ
 * hiện thực mặc định 501 của giao diện sinh. Đồng bộ nguồn nhân sự và đối soát (DV-04-07) ánh xạ qua
 * {@link SyncWebMapper}.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication
public class OrgController implements OrgApi {

  private final OrgUnitQueryService queries;
  private final OrgUnitCommandService commands;
  private final RequestHasher hasher;
  private final HttpServletRequest request;
  private final DiscrepancyService discrepancies;
  private final SyncRunService syncRuns;
  private final OrgImportService imports;
  private final MyContextService contexts;
  private final PublicKeyDirectoryService publicKeys;
  private final RecoveryKeyService recovery;

  public OrgController(OrgUnitQueryService queries, OrgUnitCommandService commands, RequestHasher hasher,
      HttpServletRequest request, DiscrepancyService discrepancies, SyncRunService syncRuns,
      OrgImportService imports, MyContextService contexts, PublicKeyDirectoryService publicKeys,
      RecoveryKeyService recovery) {
    this.publicKeys = publicKeys;
    this.recovery = recovery;
    this.imports = imports;
    this.contexts = contexts;
    this.queries = queries;
    this.commands = commands;
    this.discrepancies = discrepancies;
    this.syncRuns = syncRuns;
    this.hasher = hasher;
    this.request = request;
  }

  @Override
  public ResponseEntity<OrgUnit> getOrgUnit(UUID id, LocalDate asOf, OffsetDateTime recordedAsOf) {
    OrgUnitView view = queries.getUnit(actor(), id, asOf, recordedAsOf);
    return ResponseEntity.ok().eTag(etag(view.rowVersion())).body(OrgWebMapper.toDto(view));
  }

  @Override
  public ResponseEntity<OrgUnit> updateOrgUnit(String ifMatch, String idempotencyKey, UUID id, OrgUnitInput input) {
    int expected = IfMatch.parse(ifMatch);
    OrgUnitView view = commands.update(actor(), id, expected,
        hasher.of(idempotencyKey, "PUT", request.getRequestURI(), input), OrgWebMapper.toCommand(input));
    return ResponseEntity.ok().eTag(etag(view.rowVersion())).body(OrgWebMapper.toDto(view));
  }

  @Override
  public ResponseEntity<OrgChangeOrder> changeOrgUnit(String idempotencyKey, UUID id, OrgChangeRequest input) {
    ChangeResult result = commands.change(actor(), id,
        hasher.of(idempotencyKey, "POST", request.getRequestURI(), input), OrgWebMapper.toCommand(input));
    return ResponseEntity.status(201).body(OrgWebMapper.toDto(result));
  }

  @Override
  public ResponseEntity<OrgTree> getOrgTree(LocalDate asOf, OffsetDateTime recordedAsOf, OrgTreeKind tree,
      Integer depth) {
    TreeKind kind = tree == null ? null : TreeKind.valueOf(tree.getValue());
    return ResponseEntity.ok(OrgWebMapper.toDto(queries.getTree(actor(), asOf, recordedAsOf, kind,
        depth == null ? 3 : depth)));
  }

  @Override
  public ResponseEntity<OrgTree> getOrgSubtree(UUID id, LocalDate asOf, OffsetDateTime recordedAsOf, Integer depth) {
    return ResponseEntity.ok(OrgWebMapper.toDto(queries.getSubtree(actor(), id, asOf, recordedAsOf,
        depth == null ? 2 : depth)));
  }

  @Override
  public ResponseEntity<SearchOrgUnits200Response> searchOrgUnits(String q, LocalDate asOf,
      OffsetDateTime recordedAsOf, OrgTreeKind tree, Integer limit) {
    TreeKind kind = tree == null ? null : TreeKind.valueOf(tree.getValue());
    SearchOrgUnits200Response body = new SearchOrgUnits200Response();
    body.setItems(queries.search(actor(), q, asOf, recordedAsOf, kind, limit == null ? 20 : limit).stream()
        .map(OrgWebMapper::toDto).toList());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListOrgUnits200Response> listOrgUnits(String scope, String q, Integer limit, String cursor) {
    Page<UnitRef> page = queries.listUnits(actor(), scope, q, limit == null ? 20 : limit, cursor);
    ListOrgUnits200Response body = new ListOrgUnits200Response();
    body.setItems(page.items().stream().map(OrgWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListOrgUnitHistory200Response> listOrgUnitHistory(UUID id, OffsetDateTime recordedAsOf,
      Integer limit, String cursor) {
    Page<OrgUnitHistoryItem> page = queries.history(actor(), id, recordedAsOf, limit == null ? 20 : limit, cursor);
    ListOrgUnitHistory200Response body = new ListOrgUnitHistory200Response();
    body.setItems(page.items().stream().map(OrgWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListSubjectGroups200Response> listSubjectGroups(LocalDate asOf) {
    ListSubjectGroups200Response body = new ListSubjectGroups200Response();
    body.setItems(queries.listSubjectGroups(actor(), asOf).stream().map(OrgWebMapper::toDto).toList());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListOrgPersons200Response> listOrgPersons(String scope, UUID unit, String q, Integer limit,
      String cursor) {
    Page<PersonSummaryRow> page = queries.listPersons(actor(), scope, unit, q, limit == null ? 20 : limit, cursor);
    ListOrgPersons200Response body = new ListOrgPersons200Response();
    body.setItems(page.items().stream().map(OrgWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(body);
  }

  // DV-02-02 · QT-00 bước 7, SCR-0001, SCR-0002: hồ sơ, phân công của người dùng hiện tại và chọn ngữ cảnh.

  @Override
  public ResponseEntity<MyOrgProfile> getMyOrgProfile() {
    MyContextService.Profile p = contexts.profile(actor());
    MyOrgProfile body = new MyOrgProfile();
    body.setPersonId(p.personId().toString());
    body.setFullName(p.fullName());
    body.setAssignments(p.assignments().stream().map(a -> {
      var dto = OrgWebMapper.toDto(a.row());
      dto.setRoles(new java.util.ArrayList<>(a.roles()));
      return dto;
    }).toList());
    body.setLastAssignmentId(p.lastAssignmentId() == null ? null : p.lastAssignmentId().toString());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<WorkContext> setMyContext(String idempotencyKey, SetMyContextRequest input) {
    MyContextService.WorkContextView v = contexts.setContext(actor(),
        hasher.of(idempotencyKey, "PUT", request.getRequestURI(), input), input.getAssignmentId());
    WorkContext body = new WorkContext();
    body.setAssignmentId(v.assignmentId().toString());
    body.setOrgUnitId(v.orgUnitId().toString());
    body.setRoles(new java.util.ArrayList<>(v.roles()));
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListPersonAssignments200Response> listPersonAssignments(UUID id, LocalDate asOf,
      OffsetDateTime recordedAsOf) {
    ListPersonAssignments200Response body = new ListPersonAssignments200Response();
    body.setItems(queries.listPersonAssignments(actor(), id, asOf, recordedAsOf).stream()
        .map(OrgWebMapper::toDto).toList());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListOrgUnitAssignments200Response> listOrgUnitAssignments(UUID id, LocalDate asOf,
      OffsetDateTime recordedAsOf, String q, Integer limit, String cursor) {
    Page<UnitAssignmentRow> page = queries.listUnitAssignments(actor(), id, asOf, recordedAsOf, q,
        limit == null ? 20 : limit, cursor);
    ListOrgUnitAssignments200Response body = new ListOrgUnitAssignments200Response();
    body.setItems(page.items().stream().map(OrgWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(body);
  }

  // DV-04-05 · QT-01, SCR-0103: nhập cây tổ chức từ Excel, xử lý nền, theo dõi ở /api/v1/jobs/{id}.

  @Override
  public ResponseEntity<ImportOrgExcel202Response> importOrgExcel(String idempotencyKey, String file,
      LocalDate effectiveFrom) {
    byte[] content;
    try {
      // Bean request là proxy: lấy đối tượng yêu cầu thật để nhận cả bản multipart Spring đã phân tích lẫn Part.
      HttpServletRequest current = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
          .getRequest();
      MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(current, MultipartHttpServletRequest.class);
      MultipartFile mf = multipart == null ? null : multipart.getFile("file");
      Part part = mf == null ? current.getPart("file") : null;
      content = mf != null ? mf.getBytes() : part == null ? new byte[0] : part.getInputStream().readAllBytes();
    } catch (IOException | ServletException e) {
      throw new UncheckedIOException(new IOException("Không đọc được tệp tải lên", e));
    }
    // Băm khoá idempotency theo tên, ngày hiệu lực và băm nội dung tệp: cùng khoá khác tệp bị từ chối.
    Map<String, Object> fingerprint = new LinkedHashMap<>();
    fingerprint.put("file", file);
    fingerprint.put("effectiveFrom", effectiveFrom == null ? null : effectiveFrom.toString());
    fingerprint.put("sha256", HexFormat.of().formatHex(sha256(content)));
    OrgImportService.ImportJobView job = imports.accept(actor(),
        hasher.of(idempotencyKey, "POST", request.getRequestURI(), fingerprint), file, content, effectiveFrom);
    return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/" + job.id())).header("Retry-After", "2")
        .body(JobWebMapper.toDto(job));
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // DV-04-07 · QT-01, SCR-0104: đồng bộ nguồn nhân sự và đối soát chênh lệch.

  @Override
  public ResponseEntity<ListOrgDiscrepancies200Response> listOrgDiscrepancies(OrgDiscrepancyField field,
      DiscrepancySeverity severity, Boolean unresolved, Integer limit, String cursor) {
    return ResponseEntity.ok(SyncWebMapper.toDto(discrepancies.list(actor(),
        field == null ? null : field.getValue(), severity == null ? null : severity.getValue(), unresolved,
        limit == null ? 20 : limit, cursor)));
  }

  @Override
  public ResponseEntity<ResolveOrgDiscrepancies200Response> resolveOrgDiscrepancies(String idempotencyKey,
      ResolveOrgDiscrepanciesRequest body) {
    return ResponseEntity.ok(SyncWebMapper.toDto(discrepancies.resolve(actor(),
        hasher.of(idempotencyKey, "POST", request.getRequestURI(), body), body.getIds(),
        body.getResolution().getValue(), body.getEffectiveFrom(), body.getNote()), request.getRequestURI()));
  }

  @Override
  public ResponseEntity<OrgDiscrepancy> mapOrgDiscrepancy(String idempotencyKey, UUID id,
      MapOrgDiscrepancyRequest body) {
    return ResponseEntity.ok(SyncWebMapper.toDto(discrepancies.map(actor(),
        hasher.of(idempotencyKey, "POST", request.getRequestURI(), body), id, body.getPersonId())));
  }

  @Override
  public ResponseEntity<OrgDiscrepancy> assignOrgDiscrepancy(String idempotencyKey, UUID id,
      AssignOrgDiscrepancyRequest body) {
    return ResponseEntity.ok(SyncWebMapper.toDto(discrepancies.assign(actor(),
        hasher.of(idempotencyKey, "POST", request.getRequestURI(), body), id, body.getAssigneeId(),
        body.getNote())));
  }

  @Override
  public ResponseEntity<ImportOrgExcel202Response> startOrgSyncRun(String idempotencyKey,
      StartOrgSyncRunRequest body) {
    String connector = body == null || body.getConnector() == null ? null : body.getConnector().getValue();
    SyncRunService.Started started = syncRuns.start(actor(),
        hasher.of(idempotencyKey, "POST", request.getRequestURI(), body), connector);
    return ResponseEntity.accepted().location(URI.create("/api/v1/org/sync-runs"))
        .body(SyncWebMapper.job(started.runIds().getFirst(), "DANG_CHAY"));
  }

  @Override
  public ResponseEntity<ListOrgSyncRuns200Response> listOrgSyncRuns(SyncSource connector, Integer limit,
      String cursor) {
    Page<Run> page = syncRuns.list(actor(), connector == null ? null : connector.getValue(),
        limit == null ? 20 : limit, cursor);
    ListOrgSyncRuns200Response body = new ListOrgSyncRuns200Response();
    body.setItems(page.items().stream().map(SyncWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ImportOrgExcel202Response> retryOrgSyncRun(String idempotencyKey, UUID id) {
    Run run = syncRuns.retry(actor(), hasher.of(idempotencyKey, "POST", request.getRequestURI(), null), id);
    return ResponseEntity.accepted().location(URI.create("/api/v1/org/sync-runs"))
        .body(SyncWebMapper.job(run.id(), "DANG_CHAY"));
  }

  @Override
  public ResponseEntity<ListOrgSyncSchedules200Response> listOrgSyncSchedules() {
    return schedules(syncRuns.schedules(actor()));
  }

  @Override
  public ResponseEntity<ListOrgSyncSchedules200Response> saveOrgSyncSchedules(String ifMatch, String idempotencyKey,
      ListOrgSyncSchedules200Response body) {
    int expected = IfMatch.parse(ifMatch);
    return schedules(syncRuns.saveSchedules(actor(),
        hasher.of(idempotencyKey, "PUT", request.getRequestURI(), body), expected,
        body.getItems().stream().map(SyncWebMapper::toSchedule).toList()));
  }

  private static ResponseEntity<ListOrgSyncSchedules200Response> schedules(ScheduleSet set) {
    ListOrgSyncSchedules200Response body = new ListOrgSyncSchedules200Response();
    body.setItems(set.items().stream().map(SyncWebMapper::toDto).toList());
    return ResponseEntity.ok().eTag(etag(set.version())).body(body);
  }

  // CORE-07-06 · ADR-27: danh bạ khoá công khai có ký, khoá phục hồi tổ chức và nhật ký phục hồi C3.

  @Override
  public ResponseEntity<PersonPublicKey> registerMyPublicKey(String idempotencyKey, PublicKeyRegistration input) {
    PublicKeyDirectoryService.KeyView v = publicKeys.register(actor(),
        hasher.of(idempotencyKey, "POST", request.getRequestURI(), input),
        new PublicKeyDirectoryService.Registration(input.getKeyAlg() == null ? null : input.getKeyAlg().getValue(),
            input.getPublicKeySpki(), input.getSource() == null ? null : input.getSource().getValue(),
            E2eeWebMapper.instant(input.getValidTo())));
    return ResponseEntity.created(URI.create("/api/v1/org/persons/" + v.key().personId() + "/public-keys"))
        .eTag(etag(v.key().rowVersion())).body(E2eeWebMapper.toDto(v));
  }

  @Override
  public ResponseEntity<ListPersonPublicKeys200Response> listPersonPublicKeys(UUID id, Boolean includeInactive) {
    ListPersonPublicKeys200Response body = new ListPersonPublicKeys200Response();
    body.setItems(publicKeys.list(actor(), id, Boolean.TRUE.equals(includeInactive)).stream()
        .map(E2eeWebMapper::toDto).toList());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<PersonPublicKey> revokePublicKey(String idempotencyKey, String ifMatch, UUID id,
      PublicKeyRevocation input) {
    PublicKeyDirectoryService.KeyView v = publicKeys.revoke(actor(),
        hasher.of(idempotencyKey, "POST", request.getRequestURI(), input), id, IfMatch.parse(ifMatch),
        input.getReason());
    return ResponseEntity.ok().eTag(etag(v.key().rowVersion())).body(E2eeWebMapper.toDto(v));
  }

  @Override
  public ResponseEntity<ListE2eeDirectoryKeys200Response> listE2eeDirectoryKeys() {
    ListE2eeDirectoryKeys200Response body = new ListE2eeDirectoryKeys200Response();
    body.setItems(publicKeys.directoryKeys(actor()).stream().map(E2eeWebMapper::toDto).toList());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<RecoveryKey> getRecoveryKey() {
    StoredRecoveryKey k = recovery.current(actor());
    return ResponseEntity.ok().eTag(etag(k.rowVersion())).body(E2eeWebMapper.toDto(k));
  }

  @Override
  public ResponseEntity<RecoveryKey> rotateRecoveryKey(String idempotencyKey, RecoveryKeyInput input) {
    StoredRecoveryKey k = recovery.rotate(actor(), hasher.of(idempotencyKey, "POST", request.getRequestURI(), input),
        new RecoveryKeyService.KeyInput(input.getKeyAlg() == null ? null : input.getKeyAlg().getValue(),
            input.getPublicKeySpki(),
            input.getCustodianIds() == null ? null : new java.util.ArrayList<>(input.getCustodianIds()),
            input.getCeremonyRef()));
    return ResponseEntity.status(201).eTag(etag(k.rowVersion())).body(E2eeWebMapper.toDto(k));
  }

  @Override
  public ResponseEntity<RecoveryRecord> recordE2eeRecovery(String idempotencyKey, RecoveryRecordInput input) {
    StoredRecovery r = recovery.record(actor(), hasher.of(idempotencyKey, "POST", request.getRequestURI(), input),
        new RecoveryKeyService.RecoveryInput(input.getRecoveryKeyId(),
            input.getObjectType() == null ? null : input.getObjectType().getValue(), input.getObjectId(),
            input.getNewRecipientId(), input.getNewKeyFingerprint(),
            input.getCustodianIds() == null ? null : new java.util.ArrayList<>(input.getCustodianIds()),
            input.getReason(), input.getCeremonyRef(), E2eeWebMapper.instant(input.getPerformedAt())));
    return ResponseEntity.created(URI.create("/api/v1/org/e2ee/recoveries?objectId=" + r.objectId()))
        .body(E2eeWebMapper.toDto(r));
  }

  @Override
  public ResponseEntity<ListE2eeRecoveries200Response> listE2eeRecoveries(UUID objectId, Integer limit,
      String cursor) {
    Page<StoredRecovery> page = recovery.list(actor(), objectId, limit == null ? 20 : limit, cursor);
    ListE2eeRecoveries200Response body = new ListE2eeRecoveries200Response();
    body.setItems(page.items().stream().map(E2eeWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<RecoveryRecord> getE2eeRecovery(UUID id) {
    return ResponseEntity.ok(E2eeWebMapper.toDto(recovery.recovery(actor(), id)));
  }

  private Actor actor() {
    Object actor = request.getAttribute(ActorFilter.ATTRIBUTE);
    if (actor == null) {
      throw new IllegalStateException("Thiếu chủ thể đã xác thực");
    }
    return (Actor) actor;
  }

  private static String etag(int rowVersion) {
    return "\"" + rowVersion + "\"";
  }

}
