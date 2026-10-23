package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingPreparation;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phát hiện trường hợp đặc thù khi tạo hội nghị kiểm điểm (QT-11 bước 2), trong giao dịch tạo hội
 * nghị. Bể kết nối {@code dossier} chỉ có một kết nối nên không mở được giao dịch riêng, và trình
 * quản lý giao dịch JPA không hỗ trợ giao dịch lồng; vì vậy bộ chuyển tự đặt điểm lưu SQL và quay
 * về điểm lưu khi phát hiện lỗi, để lệnh tạo hội nghị vẫn cam kết.
 */
public class SpecialCaseMeetingPreparation implements MeetingPreparation {

  private static final Logger LOG = LoggerFactory.getLogger(SpecialCaseMeetingPreparation.class);
  static final String SAVEPOINT = "dossier_special_case";

  private final SpecialCaseService specialCases;
  private final JdbcClient jdbc;

  public SpecialCaseMeetingPreparation(SpecialCaseService specialCases, JdbcClient jdbc) {
    this.specialCases = Objects.requireNonNull(specialCases, "specialCases");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void meetingCreated(TenantId tenant, MeetingRow meeting) {
    jdbc.sql("SAVEPOINT " + SAVEPOINT).update();
    try {
      specialCases.meetingCreated(tenant, meeting);
      jdbc.sql("RELEASE SAVEPOINT " + SAVEPOINT).update();
    } catch (RuntimeException e) {
      jdbc.sql("ROLLBACK TO SAVEPOINT " + SAVEPOINT).update();
      LOG.warn(
          "Phát hiện trường hợp đặc thù lỗi meetingId={} err={}",
          meeting.id(),
          e.getClass().getName());
    }
  }
}
