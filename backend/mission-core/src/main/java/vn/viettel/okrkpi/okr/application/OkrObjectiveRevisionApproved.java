package vn.viettel.okrkpi.okr.application;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện nội bộ của MOD-01 (Spring Modulith, không có mã EVT): phiên bản điều chỉnh {@code
 * revisionId} đã được duyệt và thay phiên bản {@code previousId} (BR-02-10). CORE-01-12 lắng nghe
 * để chuyển giá trị check-in sang KR của phiên bản mới.
 */
public record OkrObjectiveRevisionApproved(
    TenantId tenant, UUID previousId, UUID revisionId, int periodBucket) {}
