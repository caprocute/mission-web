package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.notify.v1.YeuCauThongBao;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Sự kiện gói hồ sơ ghi {@code dossier.outbox_event} cùng giao dịch: sổ cái {@code sys.audit.v1}
 * ({@code KY_NOP_GOI_HO_SO}, {@code LAP_PHIEN_BAN_GOI_HO_SO}) và EVT-21 MSG-11-05 "gói đã ký và
 * nộp". Mã hành động và mã mẫu {@code GOI_HO_SO_DA_NOP} là suy luận (wiki trang 14); tham số chỉ có
 * mã gói, mã hiển thị, không có dữ liệu C2.
 */
public class OutboxPackageEvents implements PackageEvents {

  static final String AGGREGATE = "dossier_package";
  static final String AUDIT_AGGREGATE = "DossierAudit";
  static final String TEMPLATE = "GOI_HO_SO_DA_NOP";
  static final String DEEP_LINK = "/dossier/goi-ho-so/";

  private final OutboxWriter outbox;
  private final ObjectMapper json;

  public OutboxPackageEvents(OutboxWriter outbox, ObjectMapper json) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void submitted(
      TenantId tenant, PackageRow pkg, List<UUID> recipients, long version, UUID actor) {
    audit(tenant, actor, "KY_NOP_GOI_HO_SO", pkg.id(), "BR-11-10");
    if (recipients.isEmpty()) {
      return;
    }
    ObjectNode params = json.createObjectNode();
    params.put("goiId", pkg.id().toString());
    params.put("maGoi", pkg.code());
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.copyOf(recipients))
            .setTemplateCode(TEMPLATE)
            .setParams(params.toString())
            .setDeepLink(DEEP_LINK + pkg.id())
            .setPriority("BINH_THUONG")
            .setAggregateVersion((int) version)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenant,
                AGGREGATE,
                pkg.id(),
                version,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actor)));
  }

  @Override
  public void reopened(TenantId tenant, PackageRow pkg, UUID previousId, UUID actor) {
    audit(tenant, actor, "LAP_PHIEN_BAN_GOI_HO_SO", pkg.id(), "QT-13:" + previousId);
  }

  private void audit(TenantId tenant, UUID actor, String action, UUID packageId, String purpose) {
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction(action)
            .setObjectType(AGGREGATE)
            .setObjectId(packageId.toString())
            .setOutcome("THANH_CONG")
            .setPurpose(purpose)
            .setActorAssignmentId(null)
            .setActorRole(null)
            .setSystem(false)
            .setTargets(null)
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.SYS_AUDIT_V1,
                tenant,
                AUDIT_AGGREGATE,
                Ids.newUuidV7(),
                0,
                payload,
                Classification.C1)
            .withActor(ActorId.of(actor)));
  }
}
