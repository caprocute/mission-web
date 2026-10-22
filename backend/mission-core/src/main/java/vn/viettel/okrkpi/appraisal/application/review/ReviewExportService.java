package vn.viettel.okrkpi.appraisal.application.review;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.QueueItem;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewExports;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewSheets;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Xuất danh sách hồ sơ đang lọc ra XLSX (SCR-0902 "Xuất danh sách", 202 + job): chạy nền, tối đa
 * {@link #MAX_ROWS} dòng, nhãn nghiệp vụ, chỉ cột C0/C1 (không nhận xét, lý do, nội dung phiếu).
 * Tệp chỉ người tạo tác vụ tải được.
 */
public class ReviewExportService {

  public static final int MAX_ROWS = 100_000;
  public static final String LOCATION = "/api/v1/appraisals/exports/";
  static final int PAGE = 500;

  /** Cột của tệp xuất: đều là dữ liệu C0/C1 của hồ sơ. */
  public static final List<String> HEADER =
      List.of(
          "Họ và tên",
          "Đơn vị",
          "Luồng đánh giá",
          "Trạng thái",
          "Mức hệ thống gợi ý",
          "Mức tự đề xuất",
          "Mức thẩm định",
          "Mức đề xuất",
          "Mức quyết định",
          "Số lần trả lại");

  static final Map<AppraisalStatus, String> STATUS =
      Map.of(
          AppraisalStatus.CHUA_LAP, "Chưa lập",
          AppraisalStatus.TU_DANH_GIA, "Đang tự đánh giá",
          AppraisalStatus.CHO_THAM_DINH, "Chờ thẩm định",
          AppraisalStatus.DA_THAM_DINH, "Đã thẩm định",
          AppraisalStatus.CHO_QUYET_DINH, "Chờ quyết định",
          AppraisalStatus.DA_QUYET_DINH, "Đã quyết định",
          AppraisalStatus.DA_KY_SO, "Đã ký số",
          AppraisalStatus.DA_KHOA, "Đã khoá",
          AppraisalStatus.KHIEU_NAI, "Đang khiếu nại");

  static final Map<RatingLevel, String> RATING =
      Map.of(
          RatingLevel.HTXSNV, "Hoàn thành xuất sắc nhiệm vụ",
          RatingLevel.HTTNV, "Hoàn thành tốt nhiệm vụ",
          RatingLevel.HTNV, "Hoàn thành nhiệm vụ",
          RatingLevel.KHTNV, "Không hoàn thành nhiệm vụ");

  private final ReviewService reviews;
  private final ReviewSheets sheets;
  private final ReviewExports exports;
  private final Executor executor;

  public ReviewExportService(
      ReviewService reviews, ReviewSheets sheets, ReviewExports exports, Executor executor) {
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.sheets = Objects.requireNonNull(sheets, "sheets");
    this.exports = Objects.requireNonNull(exports, "exports");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /** Tạo tác vụ xuất trong phạm vi {@code scope} của người gọi; trả mã tác vụ. */
  public String export(TenantId tenant, QueueFilter filter, Scope scope, UUID initiator) {
    String jobId = UUID.randomUUID().toString();
    exports.accepted(tenant, jobId, initiator);
    executor.execute(
        () -> {
          try {
            TenantContext.call(
                tenant,
                () -> {
                  run(tenant, filter, scope, initiator, jobId);
                  return null;
                });
          } catch (Exception e) {
            exports.failed(tenant, jobId, "Không xuất được danh sách. Vui lòng thử lại.");
          }
        });
    return jobId;
  }

  /** Tệp đã xuất của người tạo tác vụ. */
  public Optional<byte[]> file(TenantId tenant, String jobId, UUID person) {
    return exports.file(tenant, jobId, person);
  }

  void run(TenantId tenant, QueueFilter filter, Scope scope, UUID initiator, String jobId)
      throws IOException {
    List<List<Object>> rows = rows(tenant, filter, scope);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    sheets.write(out, "Hồ sơ đánh giá", HEADER, rows);
    exports.put(tenant, jobId, initiator, out.toByteArray());
    exports.finished(tenant, jobId, rows.size(), LOCATION + jobId);
  }

  List<List<Object>> rows(TenantId tenant, QueueFilter filter, Scope scope) {
    List<List<Object>> rows = new ArrayList<>();
    UUID after = null;
    while (rows.size() < MAX_ROWS) {
      List<QueueItem> page =
          reviews.queue(tenant, filter, scope, after, Math.min(PAGE, MAX_ROWS - rows.size()));
      for (QueueItem item : page) {
        rows.add(row(item));
      }
      if (page.size() < PAGE) {
        break;
      }
      after = page.get(page.size() - 1).row().profile().id();
    }
    return rows;
  }

  static List<Object> row(QueueItem item) {
    ProfileSummary p = item.row().profile();
    Map<LevelKind, RatingLevel> levels = item.levels();
    return List.of(
        text(item.row().personName()),
        text(item.row().unitName()),
        "DANG".equals(p.flow()) ? "Đảng" : "Chính quyền",
        STATUS.getOrDefault(p.status(), ""),
        label(p.autoRating()),
        label(levels.get(LevelKind.TU_DE_XUAT)),
        label(levels.get(LevelKind.THAM_DINH)),
        label(levels.get(LevelKind.DE_XUAT)),
        label(levels.get(LevelKind.QUYET_DINH)),
        p.returnCount());
  }

  private static String label(RatingLevel r) {
    return r == null ? "" : RATING.get(r);
  }

  private static String text(String s) {
    return s == null ? "" : s;
  }
}
