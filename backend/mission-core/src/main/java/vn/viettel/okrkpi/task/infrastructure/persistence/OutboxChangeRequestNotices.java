package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.change.port.ChangeRequestNotices;
import vn.vtokr.notify.v1.YeuCauThongBao;

/** EVT-21 của đề nghị điều chỉnh ghi vào {@code task.outbox_event} cùng giao dịch gửi hoặc rút. */
public class OutboxChangeRequestNotices implements ChangeRequestNotices {

  static final String AGGREGATE = "task_change_request";

  private final OutboxWriter outbox;
  private final ObjectMapper json;

  public OutboxChangeRequestNotices(OutboxWriter outbox, ObjectMapper json) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void notice(Notice n) {
    ObjectNode params = json.createObjectNode();
    n.params().forEach(params::put);
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(n.recipients())
            .setTemplateCode(n.template())
            .setParams(params.toString())
            .setDeepLink("/tasks/" + n.taskId())
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) n.version())
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                n.tenant(),
                AGGREGATE,
                n.requestId(),
                n.version(),
                payload,
                Classification.C1)
            .withActor(ActorId.of(n.actorId())));
  }
}
