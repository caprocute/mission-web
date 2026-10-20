package vn.viettel.okrkpi.org.application;

import java.util.UUID;

/** Tham chiếu đơn vị cho hộp chọn theo phạm vi người dùng. */
public record UnitRef(UUID id, String name, UUID parentId) {
}
