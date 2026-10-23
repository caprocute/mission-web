package vn.viettel.okrkpi.integ.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Thu thập phần hồ sơ được phép mang theo khi điều động (kết quả xếp loại các kỳ, hồ sơ số đã ký,
 * nhận xét được phép chuyển) qua cổng nội bộ của MOD-04, MOD-06, SVC-04 dưới danh tính dịch vụ.
 */
public interface TransferContents {

  /**
   * Các phần theo thứ tự cấu hình; nguồn không trả lời thì ném {@code SourceUnavailableException}.
   */
  List<TransferCodec.Part> collect(TenantId tenant, UUID personId);
}
