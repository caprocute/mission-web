package vn.viettel.okrkpi.workflow.infrastructure.periodclose;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseRequest;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway;

/**
 * Cổng tới các bên tham gia chốt kỳ khi API nội bộ của bên đó chưa có (wiki trang 14, DV-06-06).
 *
 * <p>Mỗi bên có một chế độ: {@link Mode#CHUA_NOI} (mặc định) làm lệnh tới bên đó thất bại với thông
 * báo rõ task còn thiếu, nên saga thử lại rồi chờ người vận hành, không bao giờ coi như đã làm;
 * {@link Mode#GIA_LAP} chỉ dùng ở môi trường phát triển, coi mọi lệnh tới bên đó là xong ngay để
 * chạy thử SCR-0801 khi dịch vụ đích chưa triển khai; {@link Mode#NOI} chuyển lệnh cho adapter thật
 * của bên đã có API nội bộ (MOD-08 qua {@link HttpMod08PeriodCloseGateway}, QA-06).
 */
public class GatedPeriodCloseGateway implements PeriodCloseGateway {

  private static final Logger LOG = LoggerFactory.getLogger(GatedPeriodCloseGateway.class);

  /** Chế độ của một bên tham gia. */
  public enum Mode {
    CHUA_NOI,
    GIA_LAP,
    /** Đã nối API nội bộ của bên đó: lệnh chuyển cho adapter thật của bên tham gia. */
    NOI
  }

  private final Map<Participant, Mode> modes;
  private final Map<Participant, PeriodCloseGateway> connected;

  public GatedPeriodCloseGateway(Map<Participant, Mode> modes) {
    this(modes, Map.of());
  }

  /**
   * @param connected adapter thật của các bên ở chế độ {@link Mode#NOI}; bên ở chế độ này mà thiếu
   *     adapter thì coi như chưa nối
   */
  public GatedPeriodCloseGateway(
      Map<Participant, Mode> modes, Map<Participant, PeriodCloseGateway> connected) {
    this.modes = new EnumMap<>(Participant.class);
    for (Participant p : Participant.values()) {
      this.modes.put(p, Objects.requireNonNullElse(modes.get(p), Mode.CHUA_NOI));
    }
    this.connected = new EnumMap<>(Participant.class);
    this.connected.putAll(connected);
  }

  public Mode mode(Participant participant) {
    return modes.get(participant);
  }

  @Override
  public PreconditionReport checkPreconditions(PeriodCloseRequest request) {
    PeriodCloseGateway real = real(Participant.MOD_08);
    if (real != null) {
      return real.checkPreconditions(request);
    }
    gate(Participant.MOD_08, "kiểm tiền điều kiện", request);
    return new PreconditionReport(java.util.List.of(), java.util.List.of());
  }

  @Override
  public void submit(Command command, PeriodCloseRequest request, UUID jobId) {
    PeriodCloseGateway real = real(command.participant());
    if (real != null) {
      real.submit(command, request, jobId);
      return;
    }
    gate(command.participant(), command.name(), request);
  }

  @Override
  public JobState state(Command command, PeriodCloseRequest request, UUID jobId) {
    PeriodCloseGateway real = real(command.participant());
    if (real != null) {
      return real.state(command, request, jobId);
    }
    gate(command.participant(), command.name(), request);
    return JobState.done();
  }

  @Override
  public void undo(Command command, PeriodCloseRequest request, String undoKey) {
    PeriodCloseGateway real = real(command.participant());
    if (real != null) {
      real.undo(command, request, undoKey);
      return;
    }
    gate(command.participant(), "bù trừ " + command.name(), request);
  }

  private PeriodCloseGateway real(Participant participant) {
    return modes.get(participant) == Mode.NOI ? connected.get(participant) : null;
  }

  private void gate(Participant participant, String action, PeriodCloseRequest request) {
    if (modes.get(participant) == Mode.GIA_LAP) {
      LOG.warn(
          "Saga chốt kỳ {} GIẢ LẬP {} ở {} (chỉ môi trường phát triển)",
          request.sagaId(),
          action,
          participant);
      return;
    }
    throw new ParticipantNotConnectedException(participant, action);
  }

  /** Bên tham gia chưa có API nội bộ: lỗi kỹ thuật, saga thử lại rồi chờ người vận hành. */
  public static final class ParticipantNotConnectedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    ParticipantNotConnectedException(Participant participant, String action) {
      super(
          "Chưa nối "
              + participant
              + " cho lệnh "
              + action
              + " (chờ "
              + participant.task()
              + ", xem wiki trang 14)");
    }
  }
}
