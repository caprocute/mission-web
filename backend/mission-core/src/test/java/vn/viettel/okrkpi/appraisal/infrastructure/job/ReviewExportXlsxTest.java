package vn.viettel.okrkpi.appraisal.infrastructure.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.review.ReviewExportService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.QueueItem;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueRow;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Xuất danh sách hồ sơ (CORE-04-05): tệp XLSX chỉ có cột C0/C1, không có nhận xét, lý do hay nội
 * dung phiếu (trường C2 của {@code appraisals.yaml}); chỉ người tạo tác vụ tải được.
 */
class ReviewExportXlsxTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final QueueFilter FILTER =
      new QueueFilter(
          UUID.randomUUID(), null, null, true, java.util.Set.of("CHO_THAM_DINH"), null, null);

  private final ReviewService reviews = mock(ReviewService.class);
  private final InMemoryReviewExports store = new InMemoryReviewExports();
  private final ReviewExportService service =
      new ReviewExportService(reviews, new FastExcelReviewSheets(), store, Runnable::run);

  @Test
  void tepXuatKhongCoCotC2() throws IOException {
    UUID owner = UUID.randomUUID();
    Scope scope = new Scope("p.org_unit_id = ?", List.of(UUID.randomUUID()));
    when(reviews.queue(eq(TENANT), eq(FILTER), eq(scope), any(), any(Integer.class)))
        .thenReturn(List.of(item(UUID.randomUUID())));
    String job = service.export(TENANT, FILTER, scope, owner);
    assertThat(store.find(TENANT, job).orElseThrow().job().status()).isEqualTo(JobStatus.HOAN_TAT);
    assertThat(store.running(TENANT, owner)).isZero();
    assertThat(service.file(TENANT, job, UUID.randomUUID())).isEmpty();
    byte[] file = service.file(TENANT, job, owner).orElseThrow();

    List<List<String>> rows = new ArrayList<>();
    try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(file))) {
      for (Row r : wb.getFirstSheet().read()) {
        rows.add(
            r.stream().map(c -> c == null ? "" : c.getRawValue()).collect(Collectors.toList()));
      }
    }
    assertThat(rows.get(0)).containsExactlyElementsOf(ReviewExportService.HEADER);
    assertThat(String.join("|", rows.get(0)).toLowerCase())
        .doesNotContain("nhận xét")
        .doesNotContain("lý do")
        .doesNotContain("nội dung")
        .doesNotContain("điểm mạnh")
        .doesNotContain("đột phá");
    assertThat(rows.get(1))
        .contains("Nguyễn Văn A", "Phòng Tổng hợp", "Đảng", "Chờ thẩm định", "Hoàn thành nhiệm vụ");
  }

  private static QueueItem item(UUID id) {
    ProfileSummary p =
        new ProfileSummary(
            id,
            UUID.randomUUID(),
            20271,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "DANG",
            AppraisalStatus.CHO_THAM_DINH,
            false,
            false,
            null,
            RatingLevel.HTTNV,
            null,
            null,
            null,
            1,
            false,
            null,
            null,
            null,
            3L);
    return new QueueItem(
        new QueueRow(p, "Nguyễn Văn A", "Phòng Tổng hợp"),
        Map.of(LevelKind.TU_DE_XUAT, RatingLevel.HTNV));
  }
}
