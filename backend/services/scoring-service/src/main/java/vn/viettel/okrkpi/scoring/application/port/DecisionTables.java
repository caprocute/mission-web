package vn.viettel.okrkpi.scoring.application.port;

import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.viettel.okrkpi.scoring.domain.DecisionTableSource;

/** Cổng nạp bảng DMN vào bộ máy quyết định chạy độc lập (Flowable DMN, TL-01 SVC-07). */
public interface DecisionTables {

  /**
   * Nạp các bảng của một phiên bản một lần; lỗi định nghĩa ném {@code BusinessException} mã {@code
   * POLICY-422-FEEL}.
   */
  Compiled compile(String contentHash, List<DecisionTableSource> tables);

  /** Bảng đã nạp của một phiên bản. */
  interface Compiled {

    Set<String> tableKeys();

    /** Biến đầu vào của bảng (biểu thức đầu vào của từng cột, theo thứ tự cột). */
    List<String> inputs(String tableKey);

    /** Kết quả dòng khớp (tên đầu ra → giá trị); rỗng khi không dòng nào khớp. */
    Map<String, Object> evaluate(String tableKey, Map<String, Object> variables);
  }
}
