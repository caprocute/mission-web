package vn.viettel.okrkpi.dossier.domain.specialcase;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Bộ phát hiện 13 trường hợp đặc thù PL 6 (BR-11-05). Mỗi mã trong bảng {@code truong_hop_dac_thu}
 * của Policy Pack ứng với một cách đối chiếu dữ kiện; ngưỡng (tháng, tỷ lệ) và hệ quả lấy từ dòng
 * quy tắc. Mã gói khai mà bộ phát hiện chưa biết thì bỏ qua để gói mới không làm hỏng việc lập hồ
 * sơ. Biên theo TL-02b: điều động "từ 2 tháng trở lên" nên đúng 2 tháng là §2; nghỉ "trên 50%" nên
 * đúng một nửa kỳ không là §4.
 */
public final class SpecialCaseDetector {

  public static final String DIEU_DONG_DUOI_2_THANG = "DIEU_DONG_DUOI_2_THANG";
  public static final String DIEU_DONG_TU_2_THANG = "DIEU_DONG_TU_2_THANG";
  public static final String KIEM_NHIEM = "KIEM_NHIEM";
  public static final String NGHI_DAI_NGAY = "NGHI_DAI_NGAY";
  public static final String DI_HOC_BIET_PHAI = "DI_HOC_BIET_PHAI";
  public static final String MOI_TUYEN_DUNG_BO_NHIEM = "MOI_TUYEN_DUNG_BO_NHIEM";
  public static final String DANG_XEM_XET_XAC_MINH = "DANG_XEM_XET_XAC_MINH";
  public static final String BI_KY_LUAT = "BI_KY_LUAT";
  public static final String DON_VI_TO_CHUC_LAI = "DON_VI_TO_CHUC_LAI";
  public static final String NGHI_HUU_THOI_VIEC = "NGHI_HUU_THOI_VIEC";
  public static final String DANG_VIEN_NGOAI_HTCT = "DANG_VIEN_NGOAI_HTCT";
  public static final String KHONG_DU_DIEU_KIEN_MUC_DE_XUAT = "KHONG_DU_DIEU_KIEN_MUC_DE_XUAT";
  public static final String NGUOI_DUNG_DAU_DON_VI_KHTNV = "NGUOI_DUNG_DAU_DON_VI_KHTNV";

  private static final Map<
          String, BiFunction<SpecialCaseRule, PersonPeriodFacts, Optional<Detection>>>
      DETECTORS =
          Map.ofEntries(
              Map.entry(DIEU_DONG_DUOI_2_THANG, (r, f) -> transfer(r, f, true)),
              Map.entry(DIEU_DONG_TU_2_THANG, (r, f) -> transfer(r, f, false)),
              Map.entry(KIEM_NHIEM, SpecialCaseDetector::concurrent),
              Map.entry(NGHI_DAI_NGAY, SpecialCaseDetector::longLeave),
              Map.entry(DI_HOC_BIET_PHAI, SpecialCaseDetector::secondment),
              Map.entry(MOI_TUYEN_DUNG_BO_NHIEM, SpecialCaseDetector::newcomer),
              Map.entry(DANG_XEM_XET_XAC_MINH, SpecialCaseDetector::investigation),
              Map.entry(BI_KY_LUAT, SpecialCaseDetector::discipline),
              Map.entry(DON_VI_TO_CHUC_LAI, SpecialCaseDetector::reorganized),
              Map.entry(NGHI_HUU_THOI_VIEC, SpecialCaseDetector::leaving),
              Map.entry(DANG_VIEN_NGOAI_HTCT, SpecialCaseDetector::outsideSystem),
              Map.entry(KHONG_DU_DIEU_KIEN_MUC_DE_XUAT, SpecialCaseDetector::ineligible),
              Map.entry(NGUOI_DUNG_DAU_DON_VI_KHTNV, SpecialCaseDetector::failingHead));

  private SpecialCaseDetector() {}

  /** Mã trường hợp bộ phát hiện biết đối chiếu. */
  public static boolean supports(String code) {
    return DETECTORS.containsKey(code);
  }

  /** Các trường hợp của người trong kỳ, theo thứ tự bảng quy tắc. */
  public static List<Detection> detect(List<SpecialCaseRule> rules, PersonPeriodFacts facts) {
    List<Detection> out = new ArrayList<>();
    for (SpecialCaseRule rule : rules) {
      var detector = DETECTORS.get(rule.code());
      if (detector != null) {
        detector.apply(rule, facts).ifPresent(out::add);
      }
    }
    return out;
  }

  // ------------------------------------------------------------ §1, §2

  /** Lần chuyển vị trí công tác chính sang đơn vị khác gần nhất trong kỳ. */
  record Move(AssignmentFact from, AssignmentFact to, LocalDate until, long months) {}

  static Optional<Move> lastMove(PersonPeriodFacts f) {
    List<AssignmentFact> seats =
        f.assignments().stream()
            .filter(AssignmentFact::seat)
            .sorted(Comparator.comparing(AssignmentFact::validFrom))
            .toList();
    Move found = null;
    for (AssignmentFact to : seats) {
      LocalDate day = to.validFrom();
      if (!day.isAfter(f.periodStart()) || day.isAfter(f.periodEnd())) {
        continue;
      }
      LocalDate before = day.minusDays(1);
      for (AssignmentFact from : seats) {
        if (from != to
            && from.validFrom().isBefore(day)
            && from.covers(before)
            && !from.orgUnitId().equals(to.orgUnitId())) {
          LocalDate until = min(f.periodEnd(), to.validTo());
          found = new Move(from, to, until, months(day, until));
          break;
        }
      }
    }
    return Optional.ofNullable(found);
  }

  private static Optional<Detection> transfer(
      SpecialCaseRule r, PersonPeriodFacts f, boolean shortStay) {
    double threshold = r.number("nguong_thang", 2);
    return lastMove(f)
        .filter(m -> shortStay == (m.months() < threshold))
        .map(
            m -> {
              Map<String, Object> d = new LinkedHashMap<>();
              d.put("donViCu", m.from().orgUnitId().toString());
              d.put("donViMoi", m.to().orgUnitId().toString());
              d.put("ngayDieuDong", m.to().validFrom().toString());
              d.put("soThangTaiDonViMoi", m.months());
              return new Detection(r, r.effect(), m.to().validFrom(), m.until(), d);
            });
  }

  // ------------------------------------------------------------ §3, §5

  private static Optional<Detection> concurrent(SpecialCaseRule r, PersonPeriodFacts f) {
    return byKind(r, f, AssignmentFact.KIEM_NHIEM, "donViKiemNhiem");
  }

  private static Optional<Detection> secondment(SpecialCaseRule r, PersonPeriodFacts f) {
    Optional<Detection> seconded = byKind(r, f, AssignmentFact.BIET_PHAI, "noiBietPhai");
    if (seconded.isPresent() || !Boolean.TRUE.equals(f.supplement().longTermStudy())) {
      return seconded;
    }
    return Optional.of(
        new Detection(
            r, r.effect(), f.periodStart(), f.periodEnd(), Map.of("loai", "DI_HOC_DAI_HAN")));
  }

  private static Optional<Detection> byKind(
      SpecialCaseRule r, PersonPeriodFacts f, String kind, String key) {
    List<AssignmentFact> hits =
        f.assignments().stream()
            .filter(a -> kind.equals(a.kind()) && a.overlaps(f.periodStart(), f.periodEnd()))
            .sorted(Comparator.comparing(AssignmentFact::validFrom))
            .toList();
    if (hits.isEmpty()) {
      return Optional.empty();
    }
    LocalDate from = max(f.periodStart(), hits.get(0).validFrom());
    LocalDate to = f.periodStart();
    for (AssignmentFact a : hits) {
      to = max(to, min(f.periodEnd(), a.validTo()));
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put(key, hits.stream().map(a -> a.orgUnitId().toString()).distinct().toList());
    return Optional.of(new Detection(r, r.effect(), from, to, d));
  }

  // ------------------------------------------------------------ §6

  private static Optional<Detection> newcomer(SpecialCaseRule r, PersonPeriodFacts f) {
    double threshold = r.number("nguong_thang", 6);
    LocalDate end = f.periodEnd();
    Optional<LocalDate> first =
        f.assignments().stream()
            .map(AssignmentFact::validFrom)
            .filter(d -> !d.isAfter(end))
            .min(Comparator.naturalOrder());
    if (first.isPresent() && months(first.get(), end) < threshold) {
      return Optional.of(newcomer(r, f, "TUYEN_DUNG", first.get()));
    }
    for (AssignmentFact h : f.assignments()) {
      if (!h.head() || h.validFrom().isAfter(end) || months(h.validFrom(), end) >= threshold) {
        continue;
      }
      LocalDate before = h.validFrom().minusDays(1);
      boolean wasHead =
          f.assignments().stream().anyMatch(a -> a != h && a.head() && a.covers(before));
      if (!wasHead) {
        return Optional.of(newcomer(r, f, "BO_NHIEM", h.validFrom()));
      }
    }
    return Optional.empty();
  }

  private static Detection newcomer(
      SpecialCaseRule r, PersonPeriodFacts f, String kind, LocalDate since) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("loai", kind);
    d.put("ngay", since.toString());
    d.put("soThang", months(since, f.periodEnd()));
    return new Detection(r, r.effect(), max(f.periodStart(), since), f.periodEnd(), d);
  }

  // ------------------------------------------------------------ §10

  private static Optional<Detection> leaving(SpecialCaseRule r, PersonPeriodFacts f) {
    List<AssignmentFact> seats =
        f.assignments().stream()
            .filter(a -> a.seat() && a.overlaps(f.periodStart(), f.periodEnd()))
            .toList();
    if (seats.isEmpty()
        || f.assignments().stream().anyMatch(a -> a.seat() && a.covers(f.periodEnd()))) {
      return Optional.empty();
    }
    LocalDate last =
        seats.stream().map(AssignmentFact::validTo).max(Comparator.naturalOrder()).orElseThrow();
    LocalDate since =
        max(
            f.periodStart(),
            seats.stream()
                .map(AssignmentFact::validFrom)
                .min(Comparator.naturalOrder())
                .orElseThrow());
    long worked = months(since, last);
    Double minimum = r.number("thoi_gian_toi_thieu_thang");
    boolean insufficient =
        minimum != null && worked < minimum && r.effectWhenInsufficient() != null;
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("ngayNghi", last.plusDays(1).toString());
    d.put("soThangCongTac", worked);
    d.put("nguongToiThieu", minimum == null ? "CHUA_QUY_DINH" : minimum);
    return Optional.of(
        new Detection(r, insufficient ? r.effectWhenInsufficient() : r.effect(), since, last, d));
  }

  // ------------------------------------------------------------ dữ kiện bổ sung

  private static Optional<Detection> longLeave(SpecialCaseRule r, PersonPeriodFacts f) {
    Integer days = f.supplement().leaveDays();
    double ratio = r.number("ty_le_thoi_gian_toi_thieu", 0.5);
    if (days == null || days <= ratio * f.periodDays()) {
      return Optional.empty();
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("soNgayNghi", days);
    d.put("soNgayCuaKy", f.periodDays());
    return Optional.of(new Detection(r, r.effect(), f.periodStart(), f.periodEnd(), d));
  }

  private static Optional<Detection> investigation(SpecialCaseRule r, PersonPeriodFacts f) {
    return Boolean.TRUE.equals(f.supplement().underInvestigation())
        ? Optional.of(new Detection(r, r.effect(), f.periodStart(), null, Map.of()))
        : Optional.empty();
  }

  private static Optional<Detection> discipline(SpecialCaseRule r, PersonPeriodFacts f) {
    LocalDate on = f.supplement().disciplinedOn();
    if (!within(on, f)) {
      return Optional.empty();
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("ngayKyLuat", on.toString());
    if (f.supplement().disciplineForm() != null) {
      d.put("hinhThuc", f.supplement().disciplineForm());
    }
    return Optional.of(new Detection(r, r.effect(), on, f.periodEnd(), d));
  }

  private static Optional<Detection> reorganized(SpecialCaseRule r, PersonPeriodFacts f) {
    LocalDate on = f.supplement().unitReorganizedOn();
    return within(on, f)
        ? Optional.of(
            new Detection(r, r.effect(), on, f.periodEnd(), Map.of("ngayHieuLuc", on.toString())))
        : Optional.empty();
  }

  private static Optional<Detection> outsideSystem(SpecialCaseRule r, PersonPeriodFacts f) {
    return flag(r, f, f.supplement().outsidePoliticalSystem());
  }

  private static Optional<Detection> ineligible(SpecialCaseRule r, PersonPeriodFacts f) {
    return flag(r, f, f.supplement().proposedLevelIneligible());
  }

  private static Optional<Detection> failingHead(SpecialCaseRule r, PersonPeriodFacts f) {
    Integer units = f.supplement().subordinateUnits();
    Integer failed = f.supplement().subordinateUnitsFailed();
    boolean head =
        f.assignments().stream()
            .anyMatch(a -> a.head() && a.overlaps(f.periodStart(), f.periodEnd()));
    double ratio = r.number("nguong_ty_le", 0.5);
    if (!head || units == null || failed == null || units <= 0 || failed <= ratio * units) {
      return Optional.empty();
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("soDonViPhuTrach", units);
    d.put("soDonViKhongHoanThanh", failed);
    return Optional.of(new Detection(r, r.effect(), f.periodStart(), f.periodEnd(), d));
  }

  private static Optional<Detection> flag(SpecialCaseRule r, PersonPeriodFacts f, Boolean value) {
    return Boolean.TRUE.equals(value)
        ? Optional.of(new Detection(r, r.effect(), f.periodStart(), f.periodEnd(), Map.of()))
        : Optional.empty();
  }

  // ------------------------------------------------------------ tiện ích ngày

  private static boolean within(LocalDate day, PersonPeriodFacts f) {
    return day != null && !day.isBefore(f.periodStart()) && !day.isAfter(f.periodEnd());
  }

  /** Số tháng tròn từ {@code from} đến hết ngày {@code toInclusive}. */
  static long months(LocalDate from, LocalDate toInclusive) {
    return ChronoUnit.MONTHS.between(from, toInclusive.plusDays(1));
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return b == null || a.isBefore(b) ? a : b;
  }

  private static LocalDate max(LocalDate a, LocalDate b) {
    return a.isAfter(b) ? a : b;
  }
}
