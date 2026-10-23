package vn.viettel.okrkpi.integ.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nhập phần hồ sơ vào SVC-04 và MOD-06 của tenant đích. Bên nhập chống trùng theo {@code
 * (packageId, part.name)} nên gọi lại sau lỗi không nhập hai lần.
 */
public interface TransferImporter {

  /**
   * Lỗi tạm thời ném {@code SourceUnavailableException}; dữ liệu bị từ chối ném {@code
   * IllegalArgumentException}.
   */
  void importPart(
      TenantId tenant,
      UUID packageId,
      UUID personId,
      String sourceTenantCode,
      TransferCodec.Part part);
}
