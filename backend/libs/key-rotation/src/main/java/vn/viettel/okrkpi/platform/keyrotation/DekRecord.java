package vn.viettel.okrkpi.platform.keyrotation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekStatus;

/** Một dòng {@code keyring.dek} (chỉ bản bọc, không bao giờ có DEK rõ). */
record DekRecord(
    UUID id,
    UUID tenantId,
    DekPurpose purpose,
    LocalDate validMonth,
    byte[] wrappedDek,
    String kekRef,
    DekStatus status) {

  static final String COLUMNS = "id, tenant_id, purpose, valid_month, wrapped_dek, kek_ref, status";

  static DekRecord map(ResultSet rs) throws SQLException {
    return new DekRecord(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        DekPurpose.valueOf(rs.getString(3)),
        rs.getDate(4).toLocalDate(),
        rs.getBytes(5),
        rs.getString(6),
        DekStatus.valueOf(rs.getString(7)));
  }
}
