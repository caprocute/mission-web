package vn.viettel.okrkpi.workflow.domain.template;

import java.util.List;
import java.util.Objects;

/**
 * Kết quả bộ test và thử tải rút gọn của một phiên bản mẫu (BS-01 "Kiểm thử", "Giới hạn để không
 * ảnh hưởng mức tải"): mọi ca phải đạt và mỗi lần hoàn thành user task không quá {@code sqlBudget}
 * câu SQL.
 *
 * @param cases kết quả từng ca
 * @param bindingCount số bộ tham số {@code wf_binding} đã dùng
 * @param sqlBudget ngân sách câu SQL mỗi lần hoàn thành
 */
public record TestReport(List<TestCaseResult> cases, int bindingCount, int sqlBudget) {

  /** Ngân sách mặc định của BS-01: 2.250 câu/giây chia 150 lần hoàn thành/giây, cộng dư địa. */
  public static final int DEFAULT_SQL_BUDGET = 20;

  public TestReport {
    cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
    if (sqlBudget < 1) {
      throw new IllegalArgumentException("Ngân sách câu SQL phải dương");
    }
  }

  public int total() {
    return cases.size();
  }

  public int passedCount() {
    return (int) cases.stream().filter(TestCaseResult::passed).count();
  }

  public int maxSqlPerCompletion() {
    return cases.stream().mapToInt(TestCaseResult::maxSqlPerCompletion).max().orElse(0);
  }

  /** Mỗi lần hoàn thành nằm trong ngân sách. */
  public boolean withinBudget() {
    return maxSqlPerCompletion() <= sqlBudget;
  }

  /** Đạt: có ca, mọi ca đạt và trong ngân sách; chỉ khi đó mới được đề nghị phát hành. */
  public boolean passed() {
    return !cases.isEmpty() && passedCount() == total() && withinBudget();
  }

  /** Mô tả ngắn cho người soạn. */
  public String summary() {
    if (cases.isEmpty()) {
      return "Không chạy được ca test nào trên engine thử.";
    }
    StringBuilder out =
        new StringBuilder("Đạt ").append(passedCount()).append('/').append(total()).append(" ca");
    if (!withinBudget()) {
      out.append("; một lần hoàn thành việc cần ")
          .append(maxSqlPerCompletion())
          .append(" câu lệnh cơ sở dữ liệu, vượt ngân sách ")
          .append(sqlBudget);
    }
    return out.append('.').toString();
  }
}
