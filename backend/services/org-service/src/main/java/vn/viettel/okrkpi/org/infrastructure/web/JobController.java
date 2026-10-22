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
import vn.viettel.okrkpi.org.infrastructure.security.ActorFilter;
import vn.viettel.okrkpi.org.infrastructure.web.model.ImportOrgExcel202Response;

/**
 * {@code getJob} ({@code jobs.yaml}) cho tác vụ nền của SVC-04: mỗi service lưu tác vụ của mình với cùng lược đồ
 * {@code Job} (theo cách tenant-registry DV-03-03); định tuyến {@code /api/v1/jobs/{id}} tới đúng service ở APISIX là
 * câu hỏi mở ở wiki trang 14 (suy luận).
 */
@RestController
@ConditionalOnWebApplication
public class JobController {

  private final OrgImportService imports;
  private final HttpServletRequest request;

  public JobController(OrgImportService imports, HttpServletRequest request) {
    this.imports = imports;
    this.request = request;
  }

  @GetMapping("/api/v1/jobs/{id}")
  public ResponseEntity<ImportOrgExcel202Response> getJob(@PathVariable UUID id) {
    Actor actor = (Actor) request.getAttribute(ActorFilter.ATTRIBUTE);
    if (actor == null) {
      throw new IllegalStateException("Thiếu chủ thể đã xác thực");
    }
    return ResponseEntity.ok(JobWebMapper.toDto(imports.job(actor, id)));
  }
}
