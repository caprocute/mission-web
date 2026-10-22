package vn.viettel.okrkpi.analytics.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.domain.ReportDoc;
import vn.viettel.okrkpi.analytics.domain.TraceChain;
import vn.viettel.okrkpi.analytics.domain.Viewer;

/** Cổng của tác vụ xuất (DV-15-06). */
public final class ExportPorts {

  private ExportPorts() {}

  /** Dựng tệp: PDF qua dịch vụ chuyển đổi, XLSX ghi theo luồng, JSON chuỗi bằng chứng, gói ZIP. */
  public interface Files {
    byte[] pdf(ReportDoc doc);

    byte[] xlsx(ReportDoc doc);

    /** JSON chuỗi bằng chứng: mắt xích kèm mã băm của từng mắt xích và mã băm cả chuỗi. */
    byte[] traceJson(TraceChain chain, List<String> linkHashes, String chainHash);

    byte[] zip(Map<String, byte[]> entries);
  }

  /** Kho đối tượng của cell: lưu tệp, cấp URL tải ký ngắn hạn mỗi lần người khởi tạo hỏi. */
  public interface Store {
    /** Lưu tệp, trả khoá đối tượng. */
    String save(UUID tenant, String objectName, byte[] content, String contentType);

    String downloadUrl(String objectKey);
  }

  /** Trạng thái tác vụ và khoá chống lặp, dùng chung giữa các bản sao (Valkey). */
  public interface Jobs {
    void save(ExportJob job);

    Optional<ExportJob> find(UUID tenant, String jobId);

    /** Mã tác vụ đã gắn với khoá {@code Idempotency-Key} của người gọi. */
    Optional<String> keyed(UUID tenant, UUID user, String idempotencyKey);

    /** Gắn khoá {@code Idempotency-Key} của người gọi với tác vụ vừa nhận. */
    void bind(UUID tenant, UUID user, String idempotencyKey, String jobId);
  }

  /** Ghi việc xuất lên kênh kiểm toán {@code sys.audit.v1} (loại xuất dữ liệu). */
  public interface Audit {
    void exported(
        Viewer viewer,
        String what,
        String objectRef,
        List<String> formats,
        String jobId,
        boolean ok);
  }
}
