package vn.viettel.okrkpi.product.application.evidence;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.DraftRef;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.product.domain.evidence.SubmissionRules;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bản chiếu đối tượng minh chứng của SVC-08 trong MOD-03 (CORE-03-03): nhận {@code
 * sys.evidence.object.stored.v1} và {@code evidence.object.scanned.v1}, rồi chép kết quả quét vào
 * tệp của các phiên bản nháp đang dùng đối tượng. Tệp nhiễm giữ phiên bản ở {@code NHAP} và không
 * nộp được (BR-07-04, MSG-07-02). Chạy trong giao dịch inbox của schema product.
 */
public class EvidenceObjectProjector {

  /** Kết quả quét của SVC-08 ({@code evidence.scan_result.verdict}) sang {@code scan_status}. */
  static final Map<String, String> VERDICTS =
      Map.of("CLEAN", "SACH", "INFECTED", "NHIEM", "UNSCANNABLE", "LOI", "ERROR", "LOI");

  private final EvidenceSubmissionRepository repository;
  private final Clock clock;

  public EvidenceObjectProjector(EvidenceSubmissionRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Đối tượng đã lưu và khoá ở SVC-08. */
  @Transactional(EvidenceLifecycleService.TX)
  public void onStored(TenantId tenant, StoredObject object, UUID actorId) {
    if (repository.upsertStoredObject(tenant, object, actorId, clock.instant())) {
      refreshDrafts(tenant, object.id());
    }
  }

  /** Đối tượng đã quét mã độc; verdict lạ coi là {@code LOI} (không nộp được). */
  @Transactional(EvidenceLifecycleService.TX)
  public void onScanned(
      TenantId tenant, UUID objectId, int objectVersion, String verdict, UUID actorId) {
    String scan = VERDICTS.getOrDefault(verdict, "LOI");
    if (repository.applyScanVerdict(
        tenant, objectId, objectVersion, scan, actorId, clock.instant())) {
      refreshDrafts(tenant, objectId);
    }
  }

  private void refreshDrafts(TenantId tenant, UUID objectId) {
    for (DraftRef d : repository.syncDraftFileScan(tenant, objectId)) {
      String aggregate =
          SubmissionRules.aggregateScan(
              repository.filesOf(tenant, d.periodBucket(), d.versionId()).stream()
                  .map(FileView::file)
                  .map(EvidenceSubmissionRepository.FileRow::scanStatus)
                  .toList());
      repository.writeDraftScan(tenant, d.periodBucket(), d.versionId(), aggregate);
    }
  }
}
