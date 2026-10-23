package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository.TransferRow;
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
 * ({@code KY_NOP_GOI_HO_SO}, {@code LAP_PHIEN_BAN_GOI_HO_SO}; CORE-06-08 thêm {@code
 * XUAT_GOI_HO_SO}, {@code GUI_GOI_CON_DIEN_TW}, {@code NHAN_BIEN_NHAN_GOI_CON}), EVT-21 MSG-11-05
 * "gói đã ký và nộp" và MSG-11-06 "gửi gói con chưa thành công" (tổng hợp {@code package_transfer},
 * phiên bản là số lần gửi nên mỗi lần lỗi một thông báo). Mã hành động và mã mẫu là suy luận (wiki
 * trang 14); tham số chỉ có mã gói, mã hiển thị, không có dữ liệu C2.
 */
public class OutboxPackageEvents implements PackageEvents {

  static final String AGGREGATE = "dossier_package";
  static final String AUDIT_AGGREGATE = "DossierAudit";
  static final String TEMPLATE = "GOI_HO_SO_DA_NOP";
  static final String DEEP_LINK = "/dossier/goi-ho-so/";
  static final String TRANSFER_AGGREGATE = "package_transfer";
  static final String TRANSFER_FAILED_TEMPLATE = "GOI_CON_GUI_CHUA_THANH_CONG";

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
  public void exported(TenantId tenant, PackageRow pkg, UUID exportId, UUID actor) {
    audit(tenant, actor, "XUAT_GOI_HO_SO", pkg.id(), "SCR-1102:" + exportId);
  }

  @Override
  public void transferRequested(
      TenantId tenant, PackageRow pkg, UUID batchId, String destination, int count, UUID actor) {
    audit(
        tenant,
        actor,
        "GUI_GOI_CON_DIEN_TW",
        pkg.id(),
        "BR-11-11:" + destination + ":" + count + ":" + batchId);
  }

  @Override
  public void transferAcknowledged(TenantId tenant, PackageRow pkg, TransferRow transfer) {
    UUID actor = transfer.requestedBy() == null ? new UUID(0L, 0L) : transfer.requestedBy();
    audit(
        tenant,
        actor,
        "NHAN_BIEN_NHAN_GOI_CON",
        pkg.id(),
        "BR-11-11:" + transfer.destination() + ":" + transfer.id());
  }

  @Override
  public void transferFailed(
      TenantId tenant,
      PackageRow pkg,
      TransferRow transfer,
      List<UUID> recipients,
      String message) {
    if (recipients.isEmpty()) {
      return;
    }
    ObjectNode params = json.createObjectNode();
    params.put("goiId", pkg.id().toString());
    params.put("maGoi", pkg.code());
    params.put("noiNhan", transfer.destination());
    params.put("lyDo", transfer.lastError());
    params.put(
        "thoiDiem", transfer.nextAttemptAt() == null ? null : transfer.nextAttemptAt().toString());
    params.put("noiDung", message);
    YeuCauThongBao payload =
        YeuCauThongBao.newBuilder()
            .setRecipientIds(List.copyOf(recipients))
            .setTemplateCode(TRANSFER_FAILED_TEMPLATE)
            .setParams(params.toString())
            .setDeepLink(DEEP_LINK + pkg.id())
            .setPriority("CAO")
            .setAggregateVersion(Math.max(transfer.attempts(), 1))
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_21_YEU_CAU_THONG_BAO,
                tenant,
                TRANSFER_AGGREGATE,
                transfer.id(),
                Math.max(transfer.attempts(), 1),
                payload,
                Classification.C1)
            .withActor(
                ActorId.of(
                    transfer.requestedBy() == null ? new UUID(0L, 0L) : transfer.requestedBy())));
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
