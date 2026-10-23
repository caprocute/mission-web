package vn.viettel.okrkpi.org.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.orgimport.OrgImportService;
import vn.viettel.okrkpi.org.application.sync.SyncRunService;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.infrastructure.security.ActorFilter;
import vn.viettel.okrkpi.org.infrastructure.web.model.ImportOrgExcel202Response;

/**
 * {@code getJob} ({@code jobs.yaml}) cho tác vụ nền của SVC-04: mỗi service lưu tác vụ của mình với cùng lược đồ
 * {@code Job} (theo cách tenant-registry DV-03-03); mã tác vụ mang tiền tố {@code org-} để cổng cell định tuyến
 * {@code /api/v1/jobs/{id}} về SVC-04 (wiki 14 mục DV-01-04). Mã của lần đồng bộ đầu tiên trong một lệnh "Đồng bộ
 * ngay" là tác vụ đồng bộ (SCR-0104, HT-07); mã khác là tác vụ nhập Excel.
 */
@RestController
@ConditionalOnWebApplication
public class JobController {

  private final OrgImportService imports;
  private final SyncRunService syncRuns;
  private final HttpServletRequest request;

  public JobController(OrgImportService imports, SyncRunService syncRuns, HttpServletRequest request) {
    this.imports = imports;
    this.syncRuns = syncRuns;
    this.request = request;
  }

  @GetMapping("/api/v1/jobs/{id}")
  public ResponseEntity<ImportOrgExcel202Response> getJob(@PathVariable String id) {
    Actor actor = (Actor) request.getAttribute(ActorFilter.ATTRIBUTE);
    if (actor == null) {
      throw new IllegalStateException("Thiếu chủ thể đã xác thực");
    }
    UUID job = JobWebMapper.parseJobId(id).orElseThrow(() -> new OrgNotFoundException("Không tìm thấy tác vụ."));
    return ResponseEntity.ok(syncRuns.job(actor, job).map(SyncWebMapper::toJob)
        .orElseGet(() -> JobWebMapper.toDto(imports.job(actor, job))));
  }
}
