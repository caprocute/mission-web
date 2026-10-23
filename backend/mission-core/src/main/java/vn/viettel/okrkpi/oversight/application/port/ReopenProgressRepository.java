package vn.viettel.okrkpi.oversight.application.port;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenFollowUp;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenProgress;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tiến độ hậu kiểm theo hồ sơ ({@code oversight.reopen_progress}, bảng bổ sung) và trạng thái điều
 * phối của yêu cầu (khoá {@code followUp} trong {@code scope_json}), CORE-07-09.
 */
public interface ReopenProgressRepository {

  /** Yêu cầu {@code DA_THUC_HIEN} chưa khoá lại và chưa lập danh sách hồ sơ theo dõi, cũ trước. */
  List<UUID> executedNotSeeded(TenantId tenant, int limit);

  /**
   * Lập danh sách hồ sơ theo dõi; gọi lại không tạo trùng. Danh sách rỗng vẫn đánh dấu đã lập để
   * không tra lại.
   */
  void seed(TenantId tenant, ReopenRequest request, Collection<UUID> profileIds, UUID actorId);

  List<ReopenProgress> of(TenantId tenant, ReopenRequest request);

  /** Mã các yêu cầu {@code DA_THUC_HIEN} chưa khoá lại đang theo dõi hồ sơ này. */
  List<UUID> openRequestsOf(TenantId tenant, UUID profileId);

  void save(TenantId tenant, ReopenRequest request, ReopenProgress progress, UUID actorId);

  ReopenFollowUp followUp(TenantId tenant, ReopenRequest request);

  void saveFollowUp(TenantId tenant, ReopenRequest request, ReopenFollowUp followUp, UUID actorId);
}
