package vn.viettel.okrkpi.workflow.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;

/**
 * Kho phiên bản mẫu luồng {@code flowable.wf_template} (mọi tenant đọc được, chỉ ghi dòng của
 * mình).
 */
public interface WorkflowTemplateStore {

  Optional<TemplateVersion> find(UUID id);

  /** Phiên bản lớn nhất của mã mẫu trên mọi tenant, 0 khi chưa có. */
  int maxVersion(String templateCode);

  /** Đã có phiên bản đang soạn, kiểm thử hoặc chờ duyệt của mã mẫu. */
  boolean hasOpenDraft(String templateCode);

  /** Phiên bản đã phát hành ({@code HIEU_LUC} hoặc {@code NGUNG}) có đúng mã và số phiên bản. */
  Optional<TemplateVersion> findReleased(String templateCode, int versionNo);

  /**
   * Một trang theo thứ tự (mã mẫu, phiên bản giảm dần), sau khoá con trỏ {@code afterKey}.
   *
   * @param status lọc trạng thái, rỗng là mọi trạng thái
   * @param q từ khoá trong mã mẫu
   * @param afterKey khoá con trỏ ({@link #cursorKey}), rỗng ở trang đầu
   * @param limit số dòng tối đa
   */
  List<TemplateVersion> page(TemplateStatus status, String q, String afterKey, int limit);

  /** Khoá con trỏ của một dòng. */
  static String cursorKey(TemplateVersion t) {
    return t.templateCode() + "@" + String.format("%06d", 999_999 - t.versionNo());
  }

  /** Số ca của lần chạy test gần nhất theo phiên bản ({@code testCount}). */
  Map<UUID, Integer> testCounts(Collection<UUID> templateIds);

  /** Đường dẫn merge request của các phiên bản đã đề nghị phát hành. */
  Map<UUID, String> mergeRequestUrls(Collection<UUID> templateIds);

  TemplateVersion insert(TemplateVersion version);

  /**
   * Ghi đè khi {@code row_version} còn bằng {@code expectedRowVersion}; rỗng khi đã có người khác
   * ghi.
   */
  Optional<TemplateVersion> update(TemplateVersion version, long expectedRowVersion);
}
