package vn.viettel.okrkpi.okr.infrastructure.web;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller thử đặt trong gói module {@code okr} (TL-01) để kiểm suy ra MODULE từ gói. */
@RestController
public class OkrProbeController {

  @GetMapping("/okr-probe/optimistic")
  void optimistic() {
    throw new OptimisticLockingFailureException("update okr.objective set row_version=?");
  }
}
