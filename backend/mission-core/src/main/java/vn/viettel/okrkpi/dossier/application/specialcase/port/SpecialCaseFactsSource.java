package vn.viettel.okrkpi.dossier.application.specialcase.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.domain.specialcase.AssignmentFact;
import vn.viettel.okrkpi.dossier.domain.specialcase.PersonPeriodFacts;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Gom dữ kiện của một người trong kỳ [{@code start}, {@code end}]: phân công từ bản sao tổ chức,
 * cộng {@code overlay} (phân công vừa nhận qua EVT-15, đè dòng cùng mã vì bản sao có thể chưa kịp
 * cập nhật) và dữ kiện nhân sự bổ sung nếu có nguồn.
 */
public interface SpecialCaseFactsSource {

  PersonPeriodFacts collect(
      TenantId tenant, UUID personId, LocalDate start, LocalDate end, List<AssignmentFact> overlay);
}
