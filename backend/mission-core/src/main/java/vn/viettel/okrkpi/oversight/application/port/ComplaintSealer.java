package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mã hoá trường C2 của khiếu nại (BR-12-10, ADR-06) bằng DEK mục đích {@code KHIEU_NAI}; AAD gồm
 * tenant, tên cột đủ và mã dòng. Mỗi lần mở được ghi sổ cái truy cập C2.
 */
public interface ComplaintSealer {

  Sealed seal(TenantId tenant, String column, UUID rowId, String plain);

  String open(TenantId tenant, String column, UUID rowId, byte[] cipher);

  record Sealed(byte[] cipher, UUID dekId) {}
}
