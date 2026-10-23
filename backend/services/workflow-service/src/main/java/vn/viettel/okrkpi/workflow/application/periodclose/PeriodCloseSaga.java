package vn.viettel.okrkpi.workflow.application.periodclose;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway.Command;
import vn.viettel.okrkpi.workflow.application.saga.SagaBlueprint;
import vn.viettel.okrkpi.workflow.domain.saga.RetryPolicy;
import vn.viettel.okrkpi.workflow.domain.saga.SagaDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Saga {@code CHOT_KY} (QT-08, SCR-0801, DV-06-06): sáu bước theo thứ tự TL-02a, đánh số 1..6 trùng
 * {@code config.period_lock_step.step_no} của MOD-08 (bước 0..5 của TL-02a).
 *
 * <p>Thứ tự chọn theo TL-02a chứ không theo TL-03: EVT-09 phát khi kỳ vào {@code DANG_KHOA} (bước
 * 2) vì SVC-07 và MOD-04 tiêu thụ EVT-09 để tính điểm và lập hồ sơ; {@code DA_KHOA} đặt ở bước cuối
 * sau ký và niêm phong. Điểm không quay lại là lúc bước ký số lô hoàn tất (BR-08-09: chữ ký đã hoàn
 * tất không thu hồi được); trước đó bù trừ chạy ngược, huỷ cả yêu cầu ký đang chờ, và bù trừ bước
 * khoá đưa kỳ về {@code MO}, MOD-08 phát EVT-26. Quyết định ghi ở wiki trang 14.
 *
 * <p>Saga không tự bù trừ khi một bước hết lần thử ({@code compensateOnExhaustion = false}): bù trừ
 * toàn bộ cần người duyệt thứ hai (TL-02a QT-08 bước 9), nên saga chờ người vận hành. Riêng tiền
 * điều kiện không đạt là từ chối nghiệp vụ, saga kết thúc ngay với Problem {@code
 * PERIOD-422-PRECONDITION}.
 */
public class PeriodCloseSaga implements SagaBlueprint {

  public static final String KIEM_TIEN_DIEU_KIEN = "KIEM_TIEN_DIEU_KIEN";
  public static final String DAT_DANG_KHOA = "DAT_DANG_KHOA";
  public static final String TINH_DIEM_CHOT_KY = "TINH_DIEM_CHOT_KY";
  public static final String SINH_BIEU_MAU = "SINH_BIEU_MAU";
  public static final String KY_SO_LO = "KY_SO_LO";
  public static final String HOAN_TAT = "HOAN_TAT";

  /** Ngân sách thời gian từng bước (TL-02a cột "Thời hạn", NFR-06); quá thì cảnh báo MSG-08-04. */
  public static final Map<String, Duration> BUDGETS =
      Map.of(
          KIEM_TIEN_DIEU_KIEN, Duration.ofMinutes(5),
          DAT_DANG_KHOA, Duration.ofMinutes(1),
          TINH_DIEM_CHOT_KY, Duration.ofHours(2),
          SINH_BIEU_MAU, Duration.ofHours(2),
          KY_SO_LO, Duration.ofHours(2),
          HOAN_TAT, Duration.ofMinutes(10));

  private final SagaDefinition definition;

  public PeriodCloseSaga() {
    this(Duration.ofMinutes(1));
  }

  /**
   * @param signingBackoffUnit khoảng lùi đầu của bước ký số; mặc định 1 phút, nhân đôi tới 30 phút,
   *     13 lần thử, tổng khoảng 4 giờ (kiểm thử truyền giá trị nhỏ để giả lập 4 giờ)
   */
  public PeriodCloseSaga(Duration signingBackoffUnit) {
    RetryPolicy signing =
        new RetryPolicy(13, signingBackoffUnit, 2.0, signingBackoffUnit.multipliedBy(30));
    this.definition =
        new SagaDefinition(
            SagaType.CHOT_KY,
            List.of(
                new SagaStepDefinition(
                    KIEM_TIEN_DIEU_KIEN,
                    "MOD-08 kiểm tiền điều kiện",
                    null,
                    Duration.ofMinutes(5),
                    new RetryPolicy(3, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(1)),
                    null,
                    false),
                new SagaStepDefinition(
                    DAT_DANG_KHOA,
                    Command.KHOA_KY.name(),
                    "EVT-09",
                    Duration.ofMinutes(15),
                    new RetryPolicy(5, Duration.ofSeconds(5), 2.0, Duration.ofMinutes(1)),
                    "DAT_LAI_MO",
                    false),
                new SagaStepDefinition(
                    TINH_DIEM_CHOT_KY,
                    Command.TINH_DIEM.name(),
                    "EVT-10",
                    Duration.ofHours(3),
                    new RetryPolicy(3, Duration.ofMinutes(1), 2.0, Duration.ofMinutes(10)),
                    "DANH_DAU_LO_THAY_THE",
                    false),
                new SagaStepDefinition(
                    SINH_BIEU_MAU,
                    Command.SINH_BIEU_MAU.name(),
                    "EVT-17",
                    Duration.ofHours(4),
                    new RetryPolicy(3, Duration.ofMinutes(1), 2.0, Duration.ofMinutes(10)),
                    "THU_HOI_TEP",
                    false),
                new SagaStepDefinition(
                    KY_SO_LO,
                    Command.KY_SO_LO.name(),
                    "EVT-18",
                    Duration.ofHours(2),
                    signing,
                    "HUY_YEU_CAU_KY",
                    true),
                new SagaStepDefinition(
                    HOAN_TAT,
                    Command.HOAN_TAT_KY.name() + "+" + Command.NIEM_PHONG.name(),
                    "EVT-19",
                    Duration.ofMinutes(30),
                    RetryPolicy.standard(),
                    null,
                    false)),
            false);
  }

  @Override
  public SagaDefinition definition() {
    return definition;
  }

  @Override
  public String pointOfNoReturnCode() {
    return "PERIOD-409-POINT-OF-NO-RETURN";
  }

  /** Mỗi kỳ chỉ một saga chạy hoặc đã hoàn tất (BR-08-08). */
  @Override
  public String exclusiveKey(Map<String, Object> parameters) {
    Object period = parameters.get("periodId");
    return period == null ? null : period.toString();
  }
}
