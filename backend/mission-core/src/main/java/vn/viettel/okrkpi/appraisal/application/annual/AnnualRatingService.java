package vn.viettel.okrkpi.appraisal.application.annual;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.AnnualRow;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.Counts;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.Filter;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.QuarterRef;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader.Snapshot;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bảng tổng hợp xếp loại năm và giải thích liên thông quý–năm (SCR-1001, CORE-04-10). Câu hiển thị
 * dùng nhãn nghiệp vụ, không dùng mã mức hay mã quy tắc (wiki trang 16).
 */
public class AnnualRatingService {

  static final String TX = "appraisalTransactionManager";

  static final Map<String, String> LABELS =
      Map.of(
          "HTXSNV", "Hoàn thành xuất sắc nhiệm vụ",
          "HTTNV", "Hoàn thành tốt nhiệm vụ",
          "HTNV", "Hoàn thành nhiệm vụ",
          "KHTNV", "Không hoàn thành nhiệm vụ");

  static final String[] ROMAN = {"I", "II", "III", "IV"};

  /** Một trang và số đếm của bốn thẻ. */
  public record Page(List<AnnualRow> rows, Counts counts) {}

  /** Giải thích liên thông của một hồ sơ năm. */
  public record Explanation(
      AnnualRow row,
      List<QuarterRef> quarters,
      Snapshot snapshot,
      String systemLevel,
      String formula,
      String text,
      String legalRef) {}

  private final AnnualRatingRepository repository;
  private final ScoreSnapshotReader snapshots;

  public AnnualRatingService(AnnualRatingRepository repository, ScoreSnapshotReader snapshots) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Page page(TenantId tenant, Filter filter, int limit) {
    return new Page(repository.page(tenant, filter, limit), repository.counts(tenant, filter));
  }

  /**
   * Giải thích của dòng năm {@code id}.
   *
   * @return rỗng khi không có dòng
   * @throws BusinessException ngoài phạm vi; {@code APPRAISAL-409-NOT-COMPUTED} khi chưa có điểm
   *     năm
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<Explanation> explain(
      TenantId tenant, UUID id, UUID actor, Predicate<AnnualRow> inScope) {
    Optional<AnnualRow> found = repository.find(tenant, id);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    AnnualRow row = found.get();
    boolean own =
        actor != null
            && (actor.equals(row.personId())
                || actor.equals(row.appraiserId())
                || actor.equals(row.deciderId()));
    if (!own && !inScope.test(row)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xem hồ sơ đánh giá này.");
    }
    Snapshot snapshot = snapshots.current(tenant, row.year() * 10, row.profileId()).orElse(null);
    String level =
        snapshot != null && snapshot.autoRatingCode() != null
            ? snapshot.autoRatingCode()
            : row.systemLevel();
    if (level == null) {
      throw new BusinessException(
          "APPRAISAL-409-NOT-COMPUTED",
          "Kết quả năm của đồng chí đang được tính, vui lòng quay lại sau.");
    }
    List<QuarterRef> quarters = repository.quarters(tenant, row.year(), row.personId(), row.flow());
    return Optional.of(
        new Explanation(
            row,
            quarters,
            snapshot,
            level,
            formula(quarters, row.annualScore()),
            linkageText(row, quarters),
            legalRef(row)));
  }

  /** Cột "Quy tắc liên thông" của dòng; rỗng khi không có cảnh báo. */
  public static String linkageNote(AnnualRow row) {
    if (row.reassignFlag()) {
      return "Hai quý liên tiếp không hoàn thành nhiệm vụ, đề xuất bố trí lại công việc"
          + " (QĐ khoản 15.6).";
    }
    if (row.hasKhtnvQuarter()) {
      return "Có quý không hoàn thành nhiệm vụ nên không xếp loại hoàn thành xuất sắc năm"
          + " (QĐ khoản 15.5).";
    }
    return null;
  }

  /** MSG-10-01 và MSG-10-02 bằng lời nghiệp vụ. */
  static String linkageText(AnnualRow row, List<QuarterRef> quarters) {
    List<String> weak = new ArrayList<>();
    for (QuarterRef q : quarters) {
      if ("KHTNV".equals(q.finalRating())) {
        weak.add(roman(q.quarter()));
      }
    }
    StringBuilder out = new StringBuilder();
    if (row.hasKhtnvQuarter() && !weak.isEmpty()) {
      out.append("Hồ sơ có quý ")
          .append(String.join(", ", weak))
          .append(" xếp loại Không hoàn thành nhiệm vụ nên không được xếp loại ")
          .append(LABELS.get("HTXSNV").toLowerCase(Locale.ROOT))
          .append(" năm (QĐ khoản 15.5).");
    }
    if (row.consecutiveKhtnv()) {
      if (!out.isEmpty()) {
        out.append(' ');
      }
      out.append(
          "Có hai quý liên tiếp không hoàn thành nhiệm vụ nên hồ sơ được đề xuất bố trí lại công"
              + " việc (QĐ khoản 15.6).");
    }
    return out.isEmpty() ? null : out.toString();
  }

  private static String legalRef(AnnualRow row) {
    if (row.consecutiveKhtnv()) {
      return "QĐ 366-QĐ/TW khoản 15.6";
    }
    return row.hasKhtnvQuarter() ? "QĐ 366-QĐ/TW khoản 15.5" : null;
  }

  /**
   * Công thức trung bình các quý có điểm, ví dụ {@code (91,8 + 88,6 + 90,2 + 91,2) / 4 = 90,4}; quý
   * I–III không xếp loại (không có mức quyết định) không vào trung bình, quý IV gộp vào năm (PL
   * A5).
   */
  static String formula(List<QuarterRef> quarters, BigDecimal annualScore) {
    if (annualScore == null) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (QuarterRef q : quarters) {
      if (q.totalScore() != null && (q.finalRating() != null || q.quarter() == 4)) {
        parts.add(number(q.totalScore()));
      }
    }
    if (parts.isEmpty()) {
      return null;
    }
    return "(" + String.join(" + ", parts) + ") / " + parts.size() + " = " + number(annualScore);
  }

  static String number(BigDecimal v) {
    DecimalFormat f = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.of("vi")));
    return f.format(v.setScale(1, RoundingMode.HALF_UP));
  }

  /** Số La Mã của quý (I–IV) dùng trong câu hiển thị. */
  public static String roman(int quarter) {
    return quarter >= 1 && quarter <= 4 ? ROMAN[quarter - 1] : String.valueOf(quarter);
  }
}
