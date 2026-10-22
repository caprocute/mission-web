package vn.viettel.okrkpi.appraisal.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.QueueItem;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewExports;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueRow;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Xuất danh sách hồ sơ (CORE-04-05): tác vụ lỗi, phân trang theo con trỏ. */
class ReviewExportServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final QueueFilter FILTER =
      new QueueFilter(
          UUID.randomUUID(), null, null, true, java.util.Set.of("CHO_THAM_DINH"), null, null);

  private final ReviewService reviews = mock(ReviewService.class);
  private final ReviewExports store = mock(ReviewExports.class);
  private final ReviewExportService service =
      new ReviewExportService(reviews, (out, s, h, r) -> {}, store, Runnable::run);

  @Test
  void loiKhiXuatThiTacVuThatBaiVaPhanTrang() {
    UUID owner = UUID.randomUUID();
    when(reviews.queue(any(), any(), any(), any(), any(Integer.class)))
        .thenThrow(new IllegalStateException("x"));
    String job = service.export(TENANT, FILTER, Scope.NONE, owner);
    org.mockito.Mockito.verify(store).failed(eq(TENANT), eq(job), any());
    assertThat(service.file(TENANT, job, owner)).isEmpty();

    ReviewService paged = mock(ReviewService.class);
    List<QueueItem> page = new ArrayList<>();
    for (int i = 0; i < ReviewExportService.PAGE; i++) {
      page.add(item(UUID.randomUUID()));
    }
    UUID last = page.get(page.size() - 1).row().profile().id();
    when(paged.queue(eq(TENANT), any(), any(), eq(null), any(Integer.class))).thenReturn(page);
    when(paged.queue(eq(TENANT), any(), any(), eq(last), any(Integer.class)))
        .thenReturn(List.of(item(UUID.randomUUID())));
    ReviewExportService two =
        new ReviewExportService(
            paged, (out, s, h, r) -> {}, mock(ReviewExports.class), Runnable::run);
    assertThat(two.rows(TENANT, FILTER, Scope.NONE)).hasSize(ReviewExportService.PAGE + 1);
    assertThat(two.file(TENANT, "x", UUID.randomUUID())).isEqualTo(Optional.empty());
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
