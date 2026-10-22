package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.product.application.evidence.port.ReminderNotices;
import vn.viettel.okrkpi.product.application.evidence.port.ReminderRepository;
import vn.viettel.okrkpi.product.domain.evidence.ReminderRules;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * CORE-03-08: nhắc quá hạn nghiệm thu với lịch có ngày nghỉ lễ và ngày làm bù; chờ đúng 5 ngày làm
 * việc chưa nhắc, ngày thứ 6 nhắc; chạy hai lần trong ngày chỉ phát một lời nhắc mỗi người.
 */
class EvidenceReminderServiceTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID REVIEWER = UUID.randomUUID();
  static final UUID OTHER = UUID.randomUUID();
  static final UUID BOSS = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();

  /** Nộp thứ Năm 29/4/2027 (giờ Việt Nam); thứ Sáu 30/4 nghỉ lễ; thứ Bảy 8/5 làm bù. */
  static final Instant SUBMITTED = Instant.parse("2027-04-29T03:00:00Z");

  static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2027, 4, 30));
  static final Set<LocalDate> MAKE_UP = Set.of(LocalDate.of(2027, 5, 8));

  /** Lịch giả theo quy tắc của {@link WorkingCalendarApi}. */
  static final class FakeCalendar implements WorkingCalendarApi {
    @Override
    public boolean isWorkingDay(TenantId tenantId, LocalDate d) {
      if (MAKE_UP.contains(d)) {
        return true;
      }
      if (HOLIDAYS.contains(d)) {
        return false;
      }
      return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    @Override
    public LocalDate addWorkingDays(TenantId tenantId, LocalDate from, int n) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long countWorkingDays(TenantId tenantId, LocalDate fromExclusive, LocalDate to) {
      long n = 0;
      for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
        if (isWorkingDay(tenantId, d)) {
          n++;
        }
      }
      return n;
    }

    @Override
    public WorkingCalendar celCalendar(TenantId tenantId) {
      throw new UnsupportedOperationException();
    }
  }

  /** Kho giả: hàng chờ cố định, sổ giữ chỗ trong bộ nhớ. */
  static final class FakeRepository implements ReminderRepository {
    final List<PendingReview> pending = new ArrayList<>();
    final Set<String> claims = new HashSet<>();

    @Override
    public List<PendingReview> pendingReviews(TenantId tenant) {
      return pending;
    }

    @Override
    public boolean claim(TenantId tenant, UUID recipient, String kind, LocalDate day) {
      return claims.add(recipient + ":" + kind + ":" + day);
    }
  }

  record Sent(UUID recipient, int count, int reviewers, int days) {}

  private FakeRepository repository;
  private final List<Sent> sent = new ArrayList<>();
  private EvidenceReminderService service;

  @BeforeEach
  void setUp() {
    repository = new FakeRepository();
    repository.pending.add(
        new ReminderRepository.PendingReview(UUID.randomUUID(), 1, PERIOD, REVIEWER, SUBMITTED));
    repository.pending.add(
        new ReminderRepository.PendingReview(UUID.randomUUID(), 2, PERIOD, REVIEWER, SUBMITTED));
    repository.pending.add(
        new ReminderRepository.PendingReview(
            UUID.randomUUID(), 1, PERIOD, OTHER, Instant.parse("2027-05-06T03:00:00Z")));
    repository.pending.add(
        new ReminderRepository.PendingReview(UUID.randomUUID(), 1, PERIOD, null, SUBMITTED));
    ReminderNotices notices =
        new ReminderNotices() {
          @Override
          public void overdueForReviewer(
              TenantId tenant, UUID reviewerId, int count, int days, LocalDate day) {
            sent.add(new Sent(reviewerId, count, 0, days));
          }

          @Override
          public void overdueSummary(
              TenantId tenant, UUID boss, int count, int reviewers, int days, LocalDate day) {
            sent.add(new Sent(boss, count, reviewers, days));
          }
        };
    service =
        new EvidenceReminderService(
            repository,
            notices,
            new FakeCalendar(),
            null,
            (tenant, person, on) -> List.of(BOSS, person));
  }

  @Test
  void dungNamNgayChuaNhacNgayThuSauNhacMotLan() {
    // Thứ Sáu 7/5: đã chờ đúng 5 ngày làm việc (3 đến 7/5; 30/4 nghỉ lễ) nên chưa nhắc.
    assertThat(service.remind(T, LocalDate.of(2027, 5, 7)))
        .isEqualTo(new EvidenceReminderService.Result(0, 0));
    assertThat(sent).isEmpty();
    // Chủ nhật 9/5 không phải ngày làm việc: không chạy.
    assertThat(service.remind(T, LocalDate.of(2027, 5, 9)).reviewers()).isZero();

    // Thứ Bảy 8/5 làm bù là ngày làm việc thứ 6: nhắc người nghiệm thu và tóm tắt cho cấp trên.
    EvidenceReminderService.Result first = service.remind(T, LocalDate.of(2027, 5, 8));
    assertThat(first).isEqualTo(new EvidenceReminderService.Result(1, 1));
    assertThat(sent).containsExactly(new Sent(REVIEWER, 2, 0, 5), new Sent(BOSS, 2, 1, 5));

    // Chạy lại trong ngày không phát thêm.
    assertThat(service.remind(T, LocalDate.of(2027, 5, 8)))
        .isEqualTo(new EvidenceReminderService.Result(0, 0));
    assertThat(sent).hasSize(2);
  }

  @Test
  void nguongLayTuPolicyPack() {
    PolicyQueryApi policies = mock(PolicyQueryApi.class);
    UUID version = UUID.randomUUID();
    when(policies.resolve(eq(T), any(), eq("NHA_NUOC"), eq(null), eq(null)))
        .thenReturn(
            Optional.of(
                new PolicyQueryApi.ResolvedPack(
                    UUID.randomUUID(),
                    "GOI",
                    version,
                    "1.0.0",
                    "h",
                    "k",
                    UUID.randomUUID(),
                    false)));
    when(policies.parameter(T, version, EvidenceReminderService.SLA_KEY))
        .thenReturn(Optional.of(3));
    EvidenceReminderService withPack =
        new EvidenceReminderService(
            repository,
            new ReminderNotices() {
              @Override
              public void overdueForReviewer(
                  TenantId tenant, UUID reviewerId, int count, int days, LocalDate day) {
                sent.add(new Sent(reviewerId, count, 0, days));
              }

              @Override
              public void overdueSummary(
                  TenantId tenant, UUID boss, int count, int reviewers, int days, LocalDate day) {}
            },
            new FakeCalendar(),
            policies,
            null);
    assertThat(withPack.slaDays(T, PERIOD)).isEqualTo(3);
    // Thứ Năm 6/5: đã chờ 4 ngày làm việc, vượt ngưỡng 3.
    assertThat(withPack.remind(T, LocalDate.of(2027, 5, 6)).reviewers()).isEqualTo(1);
    assertThat(sent).containsExactly(new Sent(REVIEWER, 2, 0, 3));
    assertThat(withPack.slaDays(T, null)).isEqualTo(ReminderRules.DEFAULT_SLA_DAYS);
    when(policies.resolve(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    assertThat(withPack.slaDays(T, UUID.randomUUID())).isEqualTo(ReminderRules.DEFAULT_SLA_DAYS);
  }

  @Test
  void thamSoSaiDungMacDinh() {
    assertThat(ReminderRules.slaDays(null)).isEqualTo(5);
    assertThat(ReminderRules.slaDays(7)).isEqualTo(7);
    assertThat(ReminderRules.slaDays(7.0)).isEqualTo(7);
    assertThat(ReminderRules.slaDays(2.5)).isEqualTo(5);
    assertThat(ReminderRules.slaDays(0)).isEqualTo(5);
    assertThat(ReminderRules.slaDays(500)).isEqualTo(5);
    assertThat(ReminderRules.slaDays(" 4 ")).isEqualTo(4);
    assertThat(ReminderRules.slaDays("năm")).isEqualTo(5);
    assertThat(ReminderRules.slaDays(List.of())).isEqualTo(5);
    assertThat(ReminderRules.overdue(5, 5)).isFalse();
    assertThat(ReminderRules.overdue(6, 5)).isTrue();
  }
}
