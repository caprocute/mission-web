package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.util.UUID;
import vn.viettel.okrkpi.org.domain.OrgUnitContent;

/** Nội dung sửa hoặc tạo đơn vị (SCR-0103 trường 3–9, 11). {@code validTo == null}: theo phiên bản đang sửa. */
public record UnitCommand(OrgUnitContent content, UUID parentId, LocalDate validFrom, LocalDate validTo) {
}
