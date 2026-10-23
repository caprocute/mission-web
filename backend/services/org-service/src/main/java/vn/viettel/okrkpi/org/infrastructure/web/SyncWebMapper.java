package vn.viettel.okrkpi.org.infrastructure.web;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.port.SyncRunStore.Run;
import vn.viettel.okrkpi.org.application.port.SyncScheduleStore.Schedule;
import vn.viettel.okrkpi.org.application.sync.DiscrepancyPage;
import vn.viettel.okrkpi.org.application.sync.DiscrepancyView;
import vn.viettel.okrkpi.org.application.sync.ResolveResult;
import vn.viettel.okrkpi.org.application.sync.SyncRunService.SyncJobView;
import vn.viettel.okrkpi.org.infrastructure.web.model.DiscrepancyResolution;
import vn.viettel.okrkpi.org.infrastructure.web.model.DiscrepancySeverity;
import vn.viettel.okrkpi.org.infrastructure.web.model.GetMyOrgProfile401Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ImportOrgExcel202Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgDiscrepancies200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgDiscrepancies200ResponseCounts;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgDiscrepancy;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgDiscrepancyField;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgSuggestedAction;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgSyncRun;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgSyncSchedule;
import vn.viettel.okrkpi.org.infrastructure.web.model.ResolveOrgDiscrepancies200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ResolveOrgDiscrepancies200ResponseResultsInner;
import vn.viettel.okrkpi.org.infrastructure.web.model.SyncSource;

/** Chuyển kết quả đồng bộ, đối soát sang mô hình sinh từ {@code org.yaml} (SCR-0104). */
final class SyncWebMapper {

  private SyncWebMapper() {
  }

  static ListOrgDiscrepancies200Response toDto(DiscrepancyPage page) {
    ListOrgDiscrepancies200Response body = new ListOrgDiscrepancies200Response();
    body.setItems(page.items().stream().map(SyncWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    ListOrgDiscrepancies200ResponseCounts counts = new ListOrgDiscrepancies200ResponseCounts();
    counts.setCAO(page.counts().getOrDefault("CAO", 0L).intValue());
    counts.setVUA(page.counts().getOrDefault("VUA", 0L).intValue());
    counts.setTHAP(page.counts().getOrDefault("THAP", 0L).intValue());
    body.setCounts(counts);
    page.countsByField().forEach((k, v) -> body.putCountsByFieldItem(k, v.intValue()));
    return body;
  }

  static OrgDiscrepancy toDto(DiscrepancyView v) {
    OrgDiscrepancy d = new OrgDiscrepancy();
    d.setId(v.id());
    d.setPersonId(v.personId());
    d.setPersonName(v.personName());
    d.setMaskedNationalId(v.maskedNationalId());
    d.setField(OrgDiscrepancyField.fromValue(v.field()));
    d.setSourceConnector(SyncSource.fromValue(v.sourceConnector()));
    d.setSourceValueMasked(v.sourceValueMasked());
    d.setCurrentValueMasked(v.currentValueMasked());
    d.setSeverity(DiscrepancySeverity.fromValue(v.severity()));
    d.setSuggestedAction(OrgSuggestedAction.fromValue(v.suggestedAction()));
    d.setResolution(v.resolution() == null ? null : DiscrepancyResolution.fromValue(v.resolution()));
    d.setResolvedAt(v.resolvedAt());
    d.setAssigneeName(v.assigneeName());
    d.setExternalId(v.externalId());
    d.setMatchedBy(v.matchedBy());
    d.setEffectiveFrom(v.effectiveFrom());
    d.setSyncRunId(v.syncRunId());
    return d;
  }

  static ResolveOrgDiscrepancies200Response toDto(ResolveResult r, String instance) {
    ResolveOrgDiscrepancies200Response body = new ResolveOrgDiscrepancies200Response();
    body.setAssignmentsUpdated(r.assignmentsUpdated());
    body.setResults(r.results().stream().map(i -> {
      ResolveOrgDiscrepancies200ResponseResultsInner item = new ResolveOrgDiscrepancies200ResponseResultsInner();
      item.setId(i.id());
      item.setStatus(i.status());
      if (i.code() != null) {
        GetMyOrgProfile401Response p = new GetMyOrgProfile401Response();
        p.setType("https://okrkpi.vn/problems/" + (i.status() == 404 ? "not-found"
            : i.status() == 409 ? "conflict" : "business-rule"));
        p.setTitle(i.status() == 404 ? "Không tìm thấy" : i.status() == 409 ? "Xung đột dữ liệu"
            : "Dữ liệu chưa hợp lệ");
        p.setStatus(i.status());
        p.setDetail(i.detail());
        p.setCode(i.code());
        p.setInstance(instance);
        item.setProblem(p);
      }
      return item;
    }).toList());
    return body;
  }

  static OrgSyncRun toDto(Run r) {
    OrgSyncRun d = new OrgSyncRun();
    d.setId(r.id());
    d.setConnector(SyncSource.fromValue(r.connector()));
    d.setStartedAt(r.startedAt());
    d.setFinishedAt(r.finishedAt());
    d.setStatus(OrgSyncRun.StatusEnum.fromValue(r.status()));
    d.setRecords(r.records());
    d.setChanges(r.changes());
    d.setErrors(r.errors());
    d.setDeadLetters(r.deadLetters());
    d.setReason(r.reason());
    return d;
  }

  static OrgSyncSchedule toDto(Schedule s) {
    OrgSyncSchedule d = new OrgSyncSchedule();
    d.setConnector(SyncSource.fromValue(s.connector()));
    d.setEnabled(s.enabled());
    d.setCron(s.cron());
    return d;
  }

  static Schedule toSchedule(OrgSyncSchedule s) {
    return new Schedule(s.getConnector().getValue(), Boolean.TRUE.equals(s.getEnabled()), s.getCron());
  }

  /** Phản hồi 202 dạng {@code Job}: tác vụ đồng bộ theo dõi ở danh sách lần chạy. */
  /** Đường dẫn {@code Location} của tác vụ đồng bộ theo hợp đồng {@code Accepted}: {@code /api/v1/jobs/org-…}. */
  static URI jobLocation(UUID id) {
    return URI.create("/api/v1/jobs/" + JobWebMapper.jobId(id));
  }

  static ImportOrgExcel202Response job(UUID id, String status) {
    ImportOrgExcel202Response job = new ImportOrgExcel202Response();
    job.setId(JobWebMapper.jobId(id));
    job.setType("ORG_SYNC");
    job.setStatus(ImportOrgExcel202Response.StatusEnum.fromValue(status));
    job.setProgress(0);
    job.setResultLocation("/api/v1/org/sync-runs");
    job.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    return job;
  }

  static ImportOrgExcel202Response toJob(SyncJobView v) {
    ImportOrgExcel202Response job = new ImportOrgExcel202Response();
    job.setId(JobWebMapper.jobId(v.id()));
    job.setType("ORG_SYNC");
    job.setStatus(ImportOrgExcel202Response.StatusEnum.fromValue(v.status()));
    job.setProgress(v.progress());
    job.setMessage(v.message());
    job.setUpdatedAt(v.updatedAt());
    job.setResultLocation("/api/v1/org/sync-runs");
    if ("THAT_BAI".equals(v.status())) {
      GetMyOrgProfile401Response p = new GetMyOrgProfile401Response();
      p.setType("https://okrkpi.vn/problems/sync-failed");
      p.setTitle("Chưa đồng bộ được dữ liệu nhân sự");
      p.setStatus(502);
      p.setCode("ORG-502-SYNC-FAILED");
      p.setDetail(v.message());
      job.setProblem(p);
    }
    return job;
  }
}
