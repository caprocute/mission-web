package vn.viettel.okrkpi.search.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.IndexCommand;
import vn.viettel.okrkpi.search.domain.IndexCommand.Mode;
import vn.viettel.okrkpi.search.domain.VietnameseText;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;
import vn.vtokr.task.v1.NhiemVuDaGiao;
import vn.vtokr.task.v1.TrangThaiNhiemVuDoi;

/**
 * Dựng thao tác chỉ mục từ payload sự kiện; không đọc CSDL (TL-03). Trường được giữ đi qua {@code
 * FieldPolicy}.
 *
 * <ul>
 *   <li>EVT-04 NhiemVuDaGiao: nhóm {@code assign} gồm tiêu đề, hạn, chủ trì, phối hợp, cờ then
 *       chốt. Nhiệm vụ mật ({@code isSecret}) không mang tiêu đề thật qua sự kiện, nên indexer bỏ
 *       tiêu đề và đặt {@code is_restricted} để API tìm kiếm loại khỏi kết quả (suy luận);
 *   <li>EVT-06 TrangThaiNhiemVuDoi: nhóm {@code status} gồm trạng thái và cờ quá hạn;
 *   <li>EVT-24 VanBanChiDaoDaNhan: tài liệu văn bản chỉ đạo trọn vẹn, ghi kiểu external version.
 * </ul>
 */
public final class EventMapper {

  static final String GROUP_ASSIGN = "assign";
  static final String GROUP_STATUS = "status";

  private EventMapper() {}

  public static Optional<IndexCommand> map(InboundEvent e) {
    return switch (e.payload()) {
      case NhiemVuDaGiao t -> Optional.of(assigned(e, t));
      case TrangThaiNhiemVuDoi s -> Optional.of(status(e, s));
      case VanBanChiDaoDaNhan d -> Optional.of(document(e, d));
      default -> Optional.empty();
    };
  }

  private static IndexCommand assigned(InboundEvent e, NhiemVuDaGiao t) {
    Map<String, Object> f = new HashMap<>();
    f.put("title", t.getIsSecret() ? null : VietnameseText.normalize(t.getTitle()));
    f.put("is_restricted", t.getIsSecret());
    f.put("is_key_task", t.getIsKeyTask());
    f.put("due_date", t.getDueDate() == null ? null : t.getDueDate().toString());
    f.put("lead_assignee_id", str(t.getLeadAssigneeId()));
    f.put(
        "assignee_ids",
        t.getAssigneeIds() == null
            ? List.of()
            : t.getAssigneeIds().stream().map(UUID::toString).toList());
    f.put("plan_item_id", str(t.getPlanItemId()));
    return new IndexCommand(
        e.seq(),
        e.tenantId(),
        DocType.TASK,
        t.getTaskId().toString(),
        Mode.MERGE,
        GROUP_ASSIGN,
        t.getAggregateVersion(),
        f,
        e.occurredAt());
  }

  private static IndexCommand status(InboundEvent e, TrangThaiNhiemVuDoi s) {
    Map<String, Object> f = new HashMap<>();
    f.put("status", s.getToStatus());
    f.put("is_overdue", s.getIsOverdue());
    return new IndexCommand(
        e.seq(),
        e.tenantId(),
        DocType.TASK,
        s.getTaskId().toString(),
        Mode.MERGE,
        GROUP_STATUS,
        s.getAggregateVersion(),
        f,
        e.occurredAt());
  }

  private static IndexCommand document(InboundEvent e, VanBanChiDaoDaNhan d) {
    Map<String, Object> f = new HashMap<>();
    String summary = VietnameseText.normalize(d.getSummary());
    f.put("title", summary);
    f.put("summary", summary);
    f.put("doc_number", VietnameseText.normalize(d.getDocNumber()));
    f.put("issuer", VietnameseText.normalize(d.getIssuer()));
    f.put("issued_date", d.getIssuedDate() == null ? null : d.getIssuedDate().toString());
    return new IndexCommand(
        e.seq(),
        e.tenantId(),
        DocType.SOURCE_DOC,
        d.getDocumentId().toString(),
        Mode.REPLACE,
        null,
        d.getAggregateVersion(),
        f,
        e.occurredAt());
  }

  private static String str(UUID id) {
    return id == null ? null : id.toString();
  }
}
