package vn.viettel.okrkpi.workflow.infrastructure.simulation;

import java.sql.Connection;
import java.util.Locale;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

/**
 * Đếm câu SQL ghi ({@code INSERT}, {@code UPDATE}, {@code DELETE}) mà engine thử chuẩn bị (MyBatis
 * {@code StatementHandler.prepare}), theo luồng gọi: bộ test đo số câu của mỗi lần hoàn thành user
 * task (BS-01 "Giới hạn tải": ≤ 20). Ước tính 12 đến 18 câu của TL-01 (mục "Tải CSDL của engine")
 * liệt kê các câu ghi, còn câu đọc theo khoá chính phần lớn trúng bộ đệm của phiên, nên ngân sách
 * áp cho câu ghi; số câu đọc đếm riêng để tham khảo (suy luận, wiki trang 14). Đếm ở tầng MyBatis
 * của engine thử nên không bọc {@code DataSource} hay mở kết nối trực tiếp; chèn hàng loạt tính là
 * một câu.
 */
@Intercepts(
    @Signature(
        type = StatementHandler.class,
        method = "prepare",
        args = {Connection.class, Integer.class}))
public final class SqlStatementCounter implements Interceptor {

  /** Số câu ghi và câu đọc của một lần đo. */
  public record Count(int writes, int reads) {}

  private final ThreadLocal<int[]> current = new ThreadLocal<>();

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    int[] counter = current.get();
    if (counter != null) {
      if (isWrite(((StatementHandler) invocation.getTarget()).getBoundSql().getSql())) {
        counter[0]++;
      } else {
        counter[1]++;
      }
    }
    return invocation.proceed();
  }

  static boolean isWrite(String sql) {
    String head = sql == null ? "" : sql.stripLeading().toLowerCase(Locale.ROOT);
    return head.startsWith("insert") || head.startsWith("update") || head.startsWith("delete");
  }

  /** Chạy {@code work} và trả số câu SQL đã chuẩn bị trên luồng hiện tại trong lúc chạy. */
  public Count count(Runnable work) {
    int[] previous = current.get();
    int[] counter = new int[2];
    current.set(counter);
    try {
      work.run();
      return new Count(counter[0], counter[1]);
    } finally {
      if (previous == null) {
        current.remove();
      } else {
        previous[0] += counter[0];
        previous[1] += counter[1];
        current.set(previous);
      }
    }
  }
}
