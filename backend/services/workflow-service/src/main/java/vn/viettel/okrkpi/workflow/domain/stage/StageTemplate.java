package vn.viettel.okrkpi.workflow.domain.stage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;

/**
 * Bốn mẫu luồng có người làm của ADR-04 tầng 2 phần 2 (DV-06-04). Khác ba mẫu phần 1, thao tác
 * nghiệp vụ (nộp, thẩm định, ký, kết luận, phê duyệt) diễn ra ở API của lõi, có step-up và ký số;
 * tiến trình giữ hộp việc, hạn, nhắc, chuyển cấp, giao việc có tách vai trò và chờ ký số, rồi đi
 * tiếp khi lõi báo bước đã xong (sự kiện EVT-11, EVT-13, EVT-18 hoặc cổng {@code advance}).
 *
 * <p>Mã mẫu theo TL-02b SCR-1504; luồng duyệt phát hành Policy Pack chưa có tên trong TL-02b nên
 * đặt {@code duyet-phat-hanh-policy-pack} (suy luận). {@code processKind} là giá trị {@code
 * flowable.wf_binding.process_kind}.
 */
public enum StageTemplate {
  /** QT-09, QT-10: tự đánh giá, thẩm định, đề xuất (bỏ qua được), quyết định; mẫu v3. */
  DUYET_HO_SO_DANH_GIA(
      "duyet-ho-so-danh-gia",
      "APPRAISAL_PROFILE",
      "HO_SO_DANH_GIA",
      "appraisal",
      false,
      List.of(
          new StageStep(
              "tuDanhGia",
              "TU_DANH_GIA",
              "member",
              StageStep.Target.NGUOI,
              ApprovalScope.DON_VI_TRUC_TIEP,
              3,
              1,
              OverdueAction.NHAC_LAI,
              "TU_DANH_GIA",
              false,
              false),
          new StageStep(
              "thamDinh",
              "THAM_DINH",
              "appraiser",
              StageStep.Target.NHOM,
              ApprovalScope.CAP_TREN_TRUC_TIEP,
              3,
              1,
              OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN,
              "THAM_DINH",
              false,
              false),
          new StageStep(
              "deXuat",
              "DE_XUAT",
              "manager",
              StageStep.Target.NHOM,
              ApprovalScope.DON_VI_TRUC_TIEP,
              2,
              1,
              OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN,
              null,
              true,
              false),
          new StageStep(
              "quyetDinh",
              "QUYET_DINH",
              "decider",
              StageStep.Target.NHOM,
              ApprovalScope.CAP_TREN_TRUC_TIEP,
              3,
              1,
              OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN,
              "QUYET_DINH",
              false,
              false))),
  /** QT-12: tiếp nhận 2, giải trình 3, tổng 15 ngày làm việc, nhắc trước 2 ngày; mẫu v2. */
  XU_LY_KHIEU_NAI(
      "xu-ly-khieu-nai",
      "COMPLAINT",
      "KHIEU_NAI",
      "oversight",
      true,
      List.of(
          new StageStep(
              "tiepNhan",
              "TIEP_NHAN",
              "appraiser",
              StageStep.Target.CHON,
              ApprovalScope.DON_VI_TRUC_TIEP,
              2,
              1,
              OverdueAction.CHUYEN_CAP_TREN,
              null,
              false,
              false),
          new StageStep(
              "giaiTrinh",
              "GIAI_TRINH",
              "appraiser",
              StageStep.Target.CHON,
              ApprovalScope.DON_VI_TRUC_TIEP,
              3,
              1,
              OverdueAction.NHAC_LAI,
              null,
              false,
              false),
          new StageStep(
              "ketLuan",
              "KET_LUAN",
              "appraiser",
              StageStep.Target.CHON,
              ApprovalScope.DON_VI_TRUC_TIEP,
              15,
              2,
              OverdueAction.CHUYEN_CAP_TREN,
              null,
              false,
              true),
          new StageStep(
              "kyTraLoi",
              "KY_TRA_LOI",
              "decider",
              StageStep.Target.NHOM,
              ApprovalScope.DON_VI_TRUC_TIEP,
              15,
              2,
              OverdueAction.CHUYEN_CAP_TREN,
              "TRA_LOI_KHIEU_NAI",
              false,
              true))),
  /** QT-13: hai lượt duyệt tuần tự, mỗi lượt 2 ngày làm việc, có ký; mẫu v1. */
  MO_LAI_KY_HAI_NGUOI(
      "mo-lai-ky-hai-nguoi",
      "REOPEN_REQUEST",
      "MO_LAI_KY",
      "oversight",
      true,
      List.of(
          new StageStep(
              "duyetLan1",
              "DUYET_LAN_1",
              "decider",
              StageStep.Target.CHON,
              ApprovalScope.DON_VI_TRUC_TIEP,
              2,
              1,
              OverdueAction.CHUYEN_CAP_TREN,
              "PHE_DUYET_MO_LAI",
              false,
              false),
          new StageStep(
              "duyetLan2",
              "DUYET_LAN_2",
              "decider",
              StageStep.Target.CHON,
              ApprovalScope.DON_VI_TRUC_TIEP,
              2,
              1,
              OverdueAction.CHUYEN_CAP_TREN,
              "PHE_DUYET_MO_LAI",
              false,
              false))),
  /** QT-15: phê duyệt phát hành bởi người khác người soạn, rồi chờ saga phát hành (EVT-13). */
  DUYET_PHAT_HANH_POLICY_PACK(
      "duyet-phat-hanh-policy-pack",
      "POLICY_PACK_VERSION",
      "PHAT_HANH_POLICY_PACK",
      "policy",
      true,
      List.of(
          new StageStep(
              "pheDuyet",
              "PHE_DUYET",
              "decider",
              StageStep.Target.CHON,
              ApprovalScope.DON_VI_TRUC_TIEP,
              3,
              1,
              OverdueAction.NHAC_LAI,
              "PHAT_HANH_POLICY",
              false,
              false)));

  private final String code;
  private final String businessType;
  private final String processKind;
  private final String sourceModule;
  private final boolean signCompletesStep;
  private final List<StageStep> steps;

  StageTemplate(
      String code,
      String businessType,
      String processKind,
      String sourceModule,
      boolean signCompletesStep,
      List<StageStep> steps) {
    this.code = code;
    this.businessType = businessType;
    this.processKind = processKind;
    this.sourceModule = sourceModule;
    this.signCompletesStep = signCompletesStep;
    this.steps = List.copyOf(steps);
  }

  /** Mã mẫu, cũng là khoá định nghĩa tiến trình. */
  public String code() {
    return code;
  }

  /** Loại đối tượng của khoá nghiệp vụ {@code <businessType>:<id>}. */
  public String businessType() {
    return businessType;
  }

  /** Loại quy trình của {@code wf_binding}. */
  public String processKind() {
    return processKind;
  }

  /** Module của lõi gửi yêu cầu ký ({@code sourceModule} của EVT-18). */
  public String sourceModule() {
    return sourceModule;
  }

  /**
   * EVT-18 của mốc ký cũng là tín hiệu bước đã xong (ký trả lời khiếu nại, ký duyệt mở lại, ký phê
   * duyệt phát hành); với hồ sơ đánh giá, bước xong khi nhận EVT-11 và chữ ký chỉ mở chốt chờ ký.
   */
  public boolean signCompletesStep() {
    return signCompletesStep;
  }

  /** Các bước có người làm theo thứ tự của mẫu. */
  public List<StageStep> steps() {
    return steps;
  }

  /** Bước theo mã phần tử BPMN hoặc mã nghiệp vụ. */
  public Optional<StageStep> step(String idOrCode) {
    return steps.stream()
        .filter(s -> s.elementId().equals(idOrCode) || s.code().equals(idOrCode))
        .findFirst();
  }

  /** Vị trí của bước trong mẫu; -1 khi không có. */
  public int indexOf(String elementId) {
    for (int i = 0; i < steps.size(); i++) {
      if (steps.get(i).elementId().equals(elementId)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Kết quả lõi được báo cho từng bước: nộp, duyệt, trả lại bổ sung, yêu cầu xem lại, thụ lý, không
   * thụ lý, rút, yêu cầu giải trình, kết luận, ký, từ chối.
   */
  private static final Map<String, Set<String>> OUTCOMES =
      Map.ofEntries(
          Map.entry("tuDanhGia", Set.of("NOP")),
          Map.entry("thamDinh", Set.of("DUYET", "TRA_LAI")),
          Map.entry("deXuat", Set.of("DUYET")),
          Map.entry("quyetDinh", Set.of("DUYET", "XEM_LAI")),
          Map.entry("tiepNhan", Set.of("THU_LY", "KHONG_THU_LY", "RUT")),
          Map.entry("giaiTrinh", Set.of("XONG", "RUT")),
          Map.entry("ketLuan", Set.of("GIAI_TRINH", "KET_LUAN", "RUT")),
          Map.entry("kyTraLoi", Set.of("KY")),
          Map.entry("duyetLan1", Set.of("DUYET", "TU_CHOI")),
          Map.entry("duyetLan2", Set.of("DUYET", "TU_CHOI")),
          Map.entry("pheDuyet", Set.of("DUYET", "TU_CHOI")));

  /** Kết quả hợp lệ của bước. */
  public static Set<String> outcomes(String elementId) {
    return OUTCOMES.getOrDefault(elementId, Set.of());
  }

  /** Kết quả ghi khi chữ ký (EVT-18) là tín hiệu xong bước. */
  public static String signedOutcome(String elementId) {
    return switch (elementId) {
      case "kyTraLoi" -> "KY";
      case "tuDanhGia" -> "NOP";
      default -> "DUYET";
    };
  }

  /** Biến giữ người được chọn cho bước {@code CHON}. */
  public static Optional<String> assigneeVariable(String elementId) {
    return switch (elementId) {
      case "tiepNhan", "giaiTrinh", "ketLuan" -> Optional.of(StageVariables.NGUOI_XU_LY);
      case "duyetLan1" -> Optional.of(StageVariables.NGUOI_DUYET_1);
      case "duyetLan2" -> Optional.of(StageVariables.NGUOI_DUYET_2);
      case "pheDuyet" -> Optional.of(StageVariables.NGUOI_PHE_DUYET);
      default -> Optional.empty();
    };
  }

  /** Tìm mẫu có mốc ký {@code purpose} của module {@code sourceModule} (EVT-18). */
  public static Optional<StageTemplate> bySignature(String sourceModule, String purpose) {
    for (StageTemplate t : values()) {
      if (t.sourceModule.equals(sourceModule)
          && t.steps.stream().anyMatch(s -> purpose.equals(s.signPurpose()))) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /** Tìm mẫu theo mã. */
  public static Optional<StageTemplate> byCode(String code) {
    for (StageTemplate t : values()) {
      if (t.code.equals(code)) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /** Tìm mẫu theo loại đối tượng của khoá nghiệp vụ. */
  public static Optional<StageTemplate> byBusinessType(String type) {
    for (StageTemplate t : values()) {
      if (t.businessType.equals(type)) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }
}
