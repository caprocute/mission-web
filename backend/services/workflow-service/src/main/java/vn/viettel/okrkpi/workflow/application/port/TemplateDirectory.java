package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import java.util.Optional;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateInfo;

/** Danh mục mẫu luồng đã phát hành ({@code HIEU_LUC}) cho SCR-1504. */
public interface TemplateDirectory {

  /** Các mẫu đã phát hành, sắp theo mã rồi phiên bản. */
  List<TemplateInfo> published();

  Optional<TemplateInfo> find(String key, int versionNo);
}
