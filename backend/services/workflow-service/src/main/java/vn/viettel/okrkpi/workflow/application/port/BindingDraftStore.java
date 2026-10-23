package vn.viettel.okrkpi.workflow.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.binding.BindingDraft;

/**
 * Kho tham số luồng theo phiên bản Policy Pack ({@code flowable.wf_binding_draft}, bảng bổ sung) và
 * việc chép chúng vào {@code flowable.wf_binding} khi phát hành gói.
 */
public interface BindingDraftStore {

  List<BindingDraft> list(UUID tenantId, UUID packVersionId);

  Optional<BindingDraft> find(UUID tenantId, UUID packVersionId, String processKind);

  /** Thêm mới; trả bản ghi đã lưu. */
  BindingDraft insert(BindingDraft draft, UUID actor);

  /** Ghi đè khi {@code row_version} còn bằng {@code expected}; rỗng khi đã bị ghi đồng thời. */
  Optional<BindingDraft> update(BindingDraft draft, long expected, UUID actor);

  /**
   * Chép các tham số {@code NHAP}/{@code NGUNG} của phiên bản vào {@code wf_binding} (ghi đè dòng
   * cùng khoá, nhớ giá trị cũ để gỡ), chuyển chúng sang {@code HIEU_LUC}.
   */
  List<BindingDraft> activate(UUID tenantId, UUID packVersionId, UUID actor);

  /** Gỡ các tham số {@code HIEU_LUC} của phiên bản khỏi {@code wf_binding}, trả giá trị cũ. */
  List<BindingDraft> deactivate(UUID tenantId, UUID packVersionId, UUID actor);
}
