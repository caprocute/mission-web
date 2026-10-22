package vn.viettel.okrkpi.dossier.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.contract.dossiers.model.PeriodRef;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService.Patch;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/** Đọc JSON Merge Patch, tên hiển thị và con trỏ trang của API hội nghị (CORE-06-02). */
class DossierMeetingControllerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  static Patch patch(String body) throws Exception {
    return DossierMeetingController.patch(JSON.readTree(body));
  }

  @Test
  void patchChiNhanTruongDaGui() throws Exception {
    UUID p = UUID.randomUUID();
    Patch full =
        patch(
            "{\"scheduledAt\":\"2027-01-15T08:00:00+07:00\",\"location\":null,"
                + "\"participantPersonIds\":[\""
                + p
                + "\"],\"deadlineOverrideReason\":\"Cấp uỷ thống nhất giữ ngày.\"}");
    assertThat(full.scheduledAt()).hasToString("2027-01-15T01:00:00Z");
    assertThat(full.locationSet()).isTrue();
    assertThat(full.location()).isNull();
    assertThat(full.participantPersonIds()).containsExactly(p);
    assertThat(full.deadlineOverrideReason()).startsWith("Cấp uỷ");
    Patch place = patch("{\"location\":\"Hội trường\"}");
    assertThat(place.location()).isEqualTo("Hội trường");
    assertThat(place.scheduledAt()).isNull();
    assertThat(place.participantPersonIds()).isNull();
  }

  @Test
  void patchRongHoacSaiKieuLa422() {
    assertThatThrownBy(() -> patch("{}"))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errorCode().code())
                    .isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> patch("{\"khac\":1}")).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> DossierMeetingController.patch(null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                patch(
                    "{\"scheduledAt\":5,\"location\":3,\"participantPersonIds\":\"x\","
                        + "\"deadlineOverrideReason\":1}"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly(
                        "scheduledAt",
                        "location",
                        "participantPersonIds",
                        "deadlineOverrideReason"));
    assertThatThrownBy(
            () -> patch("{\"scheduledAt\":\"14/01/2027\",\"participantPersonIds\":[\"x\",\"y\"]}"))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errors()).hasSize(2));
  }

  @Test
  void tenHienThiVaConTro() {
    PeriodRef year =
        new PeriodRef(
            UUID.randomUUID(), "Năm 2026", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    assertThat(DossierMeetingController.title("KIEM_DIEM_NAM", year))
        .isEqualTo("Hội nghị kiểm điểm cuối năm 2026");
    assertThat(DossierMeetingController.title("CHUYEN_DE", year))
        .isEqualTo("Hội nghị kiểm điểm chuyên đề năm 2026");
    assertThat(DossierMeetingController.title("KIEM_DIEM_QUY", null))
        .isEqualTo("Hội nghị kiểm điểm");
    assertThat(DossierMeetingController.title("KIEM_DIEM_QUY", new PeriodRef()))
        .isEqualTo("Hội nghị kiểm điểm");
    UUID id = UUID.randomUUID();
    assertThat(DossierMeetingController.decodeCursor(DossierMeetingController.encodeCursor(id)))
        .isEqualTo(id);
    assertThat(DossierMeetingController.decodeCursor(" ")).isNull();
    assertThatThrownBy(() -> DossierMeetingController.decodeCursor("!!"))
        .isInstanceOf(BusinessException.class);
  }
}
