package vn.viettel.okrkpi.workflow.application.periodclose.port;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseRequest;

/**
 * Các bên tham gia saga chốt kỳ (QT-08, DV-06-06): MOD-08 trong mission-core, SVC-07 tính điểm,
 * SVC-11 sinh biểu mẫu, SVC-10 ký số, SVC-09 sổ cái.
 *
 * <p>Mỗi lệnh là một tác vụ dài ở bên nhận với mã tác vụ do saga cấp ({@code jobId}), nên giao lại
 * cùng mã không tạo tác dụng phụ trùng (pod chết giữa lượt). Saga thăm dò trạng thái qua {@link
 * #state} cho tới khi bên nhận phát sự kiện trả lời mang mã tương quan (wiki trang 14, DV-06-06).
 */
public interface PeriodCloseGateway {

  /** Bước 0: MOD-08 hỏi các module và trả vi phạm (chặn chốt kỳ) cùng cảnh báo (không chặn). */
  PreconditionReport checkPreconditions(PeriodCloseRequest request);

  /** Giao lệnh dài; idempotent theo {@code jobId}. */
  void submit(Command command, PeriodCloseRequest request, UUID jobId);

  /** Trạng thái hiện tại của lệnh đã giao. */
  JobState state(Command command, PeriodCloseRequest request, UUID jobId);

  /** Hoàn tác mọi kết quả của lệnh trong saga này (bù trừ); idempotent theo {@code undoKey}. */
  void undo(Command command, PeriodCloseRequest request, String undoKey);

  /** Bên tham gia, kèm task hiện thực phía bên đó. */
  enum Participant {
    MOD_08("CORE-08-05"),
    SVC_07("DV-07-06"),
    SVC_11("DV-11-04"),
    SVC_10("DV-10-06"),
    SVC_09("DV-09-03");

    private final String task;

    Participant(String task) {
      this.task = task;
    }

    public String task() {
      return task;
    }
  }

  /** Lệnh saga gửi bên tham gia. */
  enum Command {
    /** Kỳ sang {@code DANG_KHOA}, phát EVT-09, chặn ghi, gắn cờ minh chứng, xả bằng marker. */
    KHOA_KY(Participant.MOD_08),
    /** Tác vụ {@code score_job} loại {@code CHOT_KY} theo Policy Pack ghim của kỳ (EVT-10). */
    TINH_DIEM(Participant.SVC_07),
    /** Biểu mẫu điền sẵn điểm và bảng chấm điểm tổng hợp theo đơn vị (EVT-17). */
    SINH_BIEU_MAU(Participant.SVC_11),
    /** Ký số niêm phong các bảng tổng hợp bằng chứng thư tổ chức (EVT-18). */
    KY_SO_LO(Participant.SVC_10),
    /** Kỳ {@code DA_KHOA}, mục tiêu {@code DONG}, hồ sơ {@code CHUA_LAP}, thông báo (EVT-21). */
    HOAN_TAT_KY(Participant.MOD_08),
    /** Niêm phong Merkle đột xuất cho kỳ (EVT-19). */
    NIEM_PHONG(Participant.SVC_09);

    private final Participant participant;

    Command(Participant participant) {
      this.participant = participant;
    }

    public Participant participant() {
      return participant;
    }
  }

  /** Kết quả kiểm tiền điều kiện. */
  record PreconditionReport(List<String> violations, List<String> warnings) {
    public PreconditionReport {
      violations = List.copyOf(Objects.requireNonNullElse(violations, List.of()));
      warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
    }

    public boolean passed() {
      return violations.isEmpty();
    }
  }

  /** Trạng thái tác vụ dài ở bên nhận, theo lược đồ {@code Job} của hợp đồng chung. */
  record JobState(Status status, Integer progress, String error) {
    public JobState {
      Objects.requireNonNull(status, "status");
    }

    public static JobState running(Integer progress) {
      return new JobState(Status.DANG_CHAY, progress, null);
    }

    public static JobState done() {
      return new JobState(Status.HOAN_TAT, 100, null);
    }

    public static JobState failed(String error) {
      return new JobState(Status.THAT_BAI, null, error);
    }

    /** Bên nhận chưa có tác vụ mang mã này (chưa giao, hoặc lần giao trước chưa ghi được). */
    public static JobState missing() {
      return new JobState(Status.CHUA_CO, null, null);
    }

    public enum Status {
      CHUA_CO,
      DANG_CHAY,
      HOAN_TAT,
      THAT_BAI
    }
  }
}
