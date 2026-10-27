package vn.viettel.okrkpi.appraisal.application.unitresult;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository.Filter;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository.UnitResultRow;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code listUnitAppraisalResults} (AP-09, SCR-1603 bước đơn vị): kiểm tham số, đọc một trang hồ sơ
 * trong phạm vi ABAC đã dịch sang SQL và tính con trỏ trang sau. Thao tác chỉ đọc nên không gọi
 * {@code PeriodApi.assertWritable}.
 */
public class UnitResultService {

  static final String TX = "appraisalTransactionManager";
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 200;

  /** Một trang; {@code nextAfter} là mã hồ sơ cuối khi còn trang sau, ngược lại {@code null}. */
  public record Page(List<UnitResultRow> rows, UUID nextAfter) {}

  private final UnitResultRepository repository;

  public UnitResultService(UnitResultRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /**
   * @param limit số dòng mỗi trang, {@code null} là {@value #DEFAULT_LIMIT}
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Page listUnitResults(TenantId tenant, Filter filter, Integer limit) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(filter, "filter");
    if (filter.unitId() == null) {
      throw invalid("unit", "Đồng chí chưa chọn đơn vị.");
    }
    if (filter.periodId() == null) {
      throw invalid("period", "Đồng chí chưa chọn kỳ đánh giá.");
    }
    if (filter.flow() != null
        && !"DANG".equals(filter.flow())
        && !"NHA_NUOC".equals(filter.flow())) {
      throw invalid("flow", "Luồng đánh giá không hợp lệ.");
    }
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw invalid("limit", "Số dòng mỗi trang từ 1 đến 200.");
    }
    Objects.requireNonNull(filter.scope(), "scope");
    List<UnitResultRow> rows = repository.listUnitResults(tenant, filter, size + 1);
    if (rows.size() <= size) {
      return new Page(List.copyOf(rows), null);
    }
    List<UnitResultRow> page = List.copyOf(rows.subList(0, size));
    return new Page(page, page.get(size - 1).profileId());
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
