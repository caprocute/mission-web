package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.meeting.ReviewMeetingService;
import vn.viettel.okrkpi.dossier.application.meeting.port.MeetingNotices;
import vn.viettel.okrkpi.dossier.application.meeting.port.ReviewMeetingRepository.MeetingRow;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;

/**
 * EVT-21 {@code notify.message.requested.v1} mời thành viên góp ý dự thảo bản kiểm điểm (MSG-11-02
 * "Đồng chí được mời góp ý cho bản kiểm điểm của đồng chí {ten} trước ngày {ngay}"), ghi {@code
 * dossier.outbox_event} cùng giao dịch gửi dự thảo. Tham số chỉ có mã người được kiểm điểm để
 * SVC-12 ghép họ tên và ngày hội nghị, không có dữ liệu C2. Mã mẫu {@code MOI_GOP_Y_KIEM_DIEM} là
 * suy luận (wiki trang 14).
 */
public class OutboxMeetingNotices implements MeetingNotices {

  static final String AGGREGATE = "review_meeting";
  static final String TEMPLATE = "MOI_GOP_Y_KIEM_DIEM";
  static final String DEEP_LINK = "/dossier/kiem-diem/";
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final OutboxWriter outbox;
  private final ObjectMapper json;

  public OutboxMeetingNotices(OutboxWriter outbox, ObjectMapper json) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void draftSent(
      TenantId tenant, MeetingRow meeting, List<UUID> recipients, long version, UUID actor) {
    ObjectNode params = json.createObjectNode();
    UUID subject =
        meeting.subjectPersonId() != null ? meeting.subjectPersonId() : meeting.chairPersonId();
    params.put("nguoiDuocKiemDiemId", subject.toString());
    params.put("ngay", DAY.format(meeting.scheduledAt().atZone(ReviewMeetingService.ZONE)));
    params.put("hoiNghiId", meeting.id().toString());
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.copyOf(recipients))
            .setTemplateCode(TEMPLATE)
            .setParams(params.toString())
            .setDeepLink(DEEP_LINK + meeting.id())
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenant,
                AGGREGATE,
                meeting.id(),
                version,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actor)));
  }
}
