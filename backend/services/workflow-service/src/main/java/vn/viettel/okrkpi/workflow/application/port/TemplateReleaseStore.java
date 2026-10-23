package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.template.Release;

/** Kho đề nghị phát hành {@code flowable.wf_template_release} (bảng bổ sung). */
public interface TemplateReleaseStore {

  void insert(Release release, UUID actor);

  Optional<Release> find(UUID templateId);

  /** Ghi người phê duyệt và saga, chuyển {@code DA_DUYET}. */
  void approved(UUID templateId, UUID approver, UUID sagaId);

  /**
   * Pipeline báo đã ký: ghi chữ ký, chuyển {@code DA_KY}; trả {@code false} khi đã ghi trước đó.
   */
  boolean signed(UUID templateId, byte[] signature, String pipelineRef);

  /** Chữ ký đã ghi vào phiên bản mẫu. */
  void applied(UUID templateId);

  /** Các đề nghị đã ký mà saga chưa áp dụng, để đối chiếu trả lời bị lỡ. */
  List<Release> signedNotApplied(int limit);
}
