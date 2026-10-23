package vn.viettel.okrkpi.okr.infrastructure.web;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectiveRef;
import vn.viettel.okrkpi.contract.okr.model.OkrObjectiveStatus;
import vn.viettel.okrkpi.okr.application.OkrReadService;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điền các trường tên rời mà {@code RefNameAdvice} không điền được vì thân trả về không mang mã đơn
 * vị: {@code OkrObjectiveRef.unitName} cùng {@code code}, {@code title} của mục tiêu cấp trên trong
 * danh sách, {@code unitName} của nút bản đồ liên kết, {@code peerUnitName} của liên kết ngang
 * (QA-03/04). Mỗi lời gọi tra mục tiêu một truy vấn và tên đơn vị một lượt qua {@link
 * AccessDirectory} trên {@code org_replica}; không có bean danh bạ thì tên giữ rỗng.
 */
@Component
class OkrNameFiller {

  private final OkrReadService reads;
  private final ObjectProvider<AccessDirectory> directory;

  OkrNameFiller(OkrReadService reads, ObjectProvider<AccessDirectory> directory) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  /** Tên đơn vị theo mã, một lượt; thiếu danh bạ hoặc mã không biết thì vắng. */
  Map<UUID, String> unitNames(TenantId tenant, Collection<UUID> unitIds) {
    Set<UUID> ids = new LinkedHashSet<>(unitIds);
    ids.remove(null);
    AccessDirectory dir = directory.getIfAvailable();
    return dir == null || ids.isEmpty() ? Map.of() : dir.unitNames(tenant, ids);
  }

  /**
   * Bổ sung {@code code}, {@code title}, {@code status} còn trống và {@code unitName} (đơn vị sở
   * hữu) cho các mục tiêu rút gọn; phần tử {@code null} bỏ qua.
   */
  void objectiveRefs(TenantId tenant, Collection<OkrObjectiveRef> refs) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (OkrObjectiveRef ref : refs) {
      if (ref != null && blank(ref.getUnitName())) {
        ids.add(ref.getId());
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Map<UUID, ObjectiveRow> rows = new HashMap<>();
    reads.objectiveRows(tenant, ids).forEach(r -> rows.put(r.id(), r));
    Map<UUID, String> units =
        unitNames(tenant, rows.values().stream().map(ObjectiveRow::ownerUnitId).toList());
    for (OkrObjectiveRef ref : refs) {
      ObjectiveRow row = ref == null ? null : rows.get(ref.getId());
      if (row == null) {
        continue;
      }
      if (blank(ref.getCode())) {
        ref.code(row.code()).title(row.title());
      }
      if (ref.getStatus() == null) {
        ref.status(OkrObjectiveStatus.fromValue(row.status()));
      }
      String unit = units.get(row.ownerUnitId());
      if (blank(ref.getUnitName()) && unit != null) {
        ref.unitName(unit);
      }
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isEmpty();
  }
}
