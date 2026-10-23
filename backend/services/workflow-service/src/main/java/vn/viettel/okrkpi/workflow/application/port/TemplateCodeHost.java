package vn.viettel.okrkpi.workflow.application.port;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Hệ quản lý mã của repo mẫu luồng (BS-01 "Đồng bộ với kho mã"): giao diện không thay thế kho mã mà
 * ghi vào nó qua merge request; CI chạy lại linter, test và ký gói mẫu (cosign).
 */
public interface TemplateCodeHost {

  /**
   * Gói gửi vào repo: tệp BPMN, khai báo tham số, bộ test.
   *
   * @param templateId phiên bản mẫu
   * @param templateCode mã mẫu
   * @param versionNo phiên bản
   * @param files đường dẫn tệp trong repo → nội dung
   * @param title tiêu đề merge request
   */
  record ReleasePackage(
      UUID templateId,
      String templateCode,
      int versionNo,
      Map<String, String> files,
      String title) {
    public ReleasePackage {
      Objects.requireNonNull(templateId, "templateId");
      Objects.requireNonNull(templateCode, "templateCode");
      files = Map.copyOf(files);
    }

    /** Nhánh nguồn của merge request. */
    public String branch() {
      return "mau-luong/" + templateCode + "-v" + versionNo;
    }
  }

  /** Merge request đã mở. */
  record MergeRequest(String ref, String url) {
    public MergeRequest {
      Objects.requireNonNull(ref, "ref");
    }
  }

  /** Mở merge request; gọi lại cùng gói không mở trùng. */
  MergeRequest open(ReleasePackage pkg, UUID actor);

  /** Duyệt merge request thay người phê duyệt (CI gộp và ký sau đó); gọi lại không lỗi. */
  void approve(UUID templateId, String mergeRequestRef, UUID approver);
}
