package vn.viettel.okrkpi.org.application;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.port.TreeQueryPort.TreeRow;

/** Đơn vị khớp tìm kiếm kèm tổ tiên từ gốc. */
public record SearchHit(UUID id, String code, String name, String unitType, List<TreeRow> ancestors) {
}
