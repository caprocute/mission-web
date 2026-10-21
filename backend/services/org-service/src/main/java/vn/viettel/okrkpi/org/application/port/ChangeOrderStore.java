package vn.viettel.okrkpi.org.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.ChangeOrderRecord;

/** Truy cập {@code org.org_change_order}. */
public interface ChangeOrderStore {

  /** Ghi quyết định ở trạng thái đã áp dụng, {@code applied_at = now()}; ném {@code OrgConflictException} khi số
   * quyết định đã có trong tenant. */
  ChangeOrderRecord insert(ChangeOrderRecord order, Actor actor);

  /** Đơn vị tiền thân ghi trong {@code payload.predecessorUnitIds} của quyết định đã áp dụng sinh ra đơn vị. */
  List<UUID> predecessorsOf(UUID unitId);

  Map<UUID, ChangeOrderRecord> findByIds(Collection<UUID> ids);
}
