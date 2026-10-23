package vn.viettel.okrkpi.scoring.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.OpenPeriod;

/** Kỳ chưa chốt của tenant mà SVC-07 đã tính ({@code score_result} không có tác vụ chốt xong). */
public interface OpenPeriods {

  List<OpenPeriod> of(UUID tenantId);
}
