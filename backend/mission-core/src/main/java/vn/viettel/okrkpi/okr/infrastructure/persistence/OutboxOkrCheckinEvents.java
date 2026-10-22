package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinEvents;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinRow;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;
import vn.vtokr.okr.v1.KrDaCheckIn;

/**
 * Ghi EVT-02 và EVT-21 của check-in vào {@code okr.outbox_event} (CORE-01-09). Thân sự kiện chỉ có
 * mã, giá trị, mức tự tin (C1), không có kết quả nổi bật hay vướng mắc. Người nhận EVT-21 là người
 * đứng đầu đơn vị sở hữu mục tiêu theo {@code org_replica}; không tìm được ai thì không phát.
 */
public class OutboxOkrCheckinEvents implements OkrCheckinEvents {

  static final String AGGREGATE = "key_result";

  /** Mã mẫu thông báo MSG-02-01 ở SVC-12 (suy luận tên mã). */
  static final String TEMPLATE_AT_RISK = "OKR_KR_RUI_RO";

  private static final ZoneId VIET_NAM = ZoneId.of("Asia/Ho_Chi_Minh");

  private final OutboxWriter outbox;
  private final Supplier<OrgDirectoryApi> org;

  public OutboxOkrCheckinEvents(OutboxWriter outbox, Supplier<OrgDirectoryApi> org) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.org = Objects.requireNonNull(org, "org");
  }

  @Override
  public void checkedIn(
      TenantId tenant, CheckinTarget keyResult, CheckinRow checkin, long rowVersion) {
    KrDaCheckIn payload =
        KrDaCheckIn.newBuilder()
            .setKeyResultId(keyResult.keyResultId())
            .setObjectiveId(keyResult.objectiveId())
            .setValue(checkin.value().setScale(4, RoundingMode.HALF_UP))
            .setConfidence(checkin.confidence())
            .setCheckinAt(checkin.checkinAt())
            .setAggregateVersion((int) rowVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_02_KR_DA_CHECK_IN,
                tenant,
                AGGREGATE,
                keyResult.keyResultId(),
                rowVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(checkin.createdBy())));
  }

  @Override
  public void atRisk(
      TenantId tenant, CheckinTarget keyResult, CheckinRow checkin, long rowVersion) {
    OrgDirectoryApi directory = org.get();
    List<UUID> heads =
        directory == null
            ? List.of()
            : directory.headsOf(tenant, keyResult.ownerUnitId(), LocalDate.now(VIET_NAM));
    if (heads.isEmpty()) {
      return;
    }
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.copyOf(heads))
            .setTemplateCode(TEMPLATE_AT_RISK)
            .setParams("{\"ma\":" + quote(keyResult.code()) + "}")
            .setDeepLink("/okr/checkin?kr=" + keyResult.keyResultId())
            .setPriority("CAO")
            .setAggregateVersion((int) rowVersion)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenant,
                AGGREGATE,
                keyResult.keyResultId(),
                rowVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(checkin.createdBy())));
  }

  static String quote(String s) {
    if (s == null) {
      return "\"\"";
    }
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
