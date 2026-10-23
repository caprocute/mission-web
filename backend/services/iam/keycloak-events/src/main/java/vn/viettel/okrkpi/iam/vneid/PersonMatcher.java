package vn.viettel.okrkpi.iam.vneid;

import java.io.IOException;
import java.util.UUID;

/** Tra người của tenant theo số định danh cá nhân (cổng nội bộ chỉ đọc của org-service, DV-02-04). */
interface PersonMatcher {

  /** {@code personId} chỉ có khi khớp đúng một người; {@code matches} là số người khớp. */
  record Match(UUID personId, int matches) {
  }

  Match match(UUID tenantId, String nationalId) throws IOException;
}
