package vn.viettel.okrkpi.integ.infrastructure.truc;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;

/**
 * Translator văn bản nhận qua Trục sang EVT-24 {@code integ.document.received.v1}, cùng mô hình
 * chuẩn nhóm 5 với vOffice: cơ quan nhận (mã định danh cơ quan) phải đã ánh xạ sang đơn vị nội bộ
 * ({@code DON_VI}), chưa ánh xạ thì từ chối vào thư chết; mã văn bản nội bộ ổn định qua {@code
 * VAN_BAN}. Gói sai cấu trúc cũng từ chối (không thử lại). Tệp đính kèm chưa chuyển sang
 * evidence-service nên {@code objectKey} để trống, như DV-13-03.
 */
class TrucTranslator implements Translator {

  static final String TOPIC = "integ.document.received.v1";
  static final String AGGREGATE = "VAN_BAN";
  static final String BAD_EDXML = "EDXML_SAI_CAU_TRUC";
  static final String UNMAPPED_UNIT = "DON_VI_CHUA_ANH_XA";
  static final String MISSING_UNIT = "THIEU_DON_VI_NHAN";

  private final int maxBytes;

  TrucTranslator(int maxBytes) {
    this.maxBytes = maxBytes;
  }

  @Override
  public List<CanonicalEvent> translate(TranslationContext ctx, InboundMessage message) {
    String id = message.externalMsgId();
    EdxmlDocument d;
    try {
      d = Edxml.parse(message.raw(), maxBytes);
    } catch (IllegalArgumentException e) {
      throw new TranslationRejectedException(BAD_EDXML, "Văn bản " + id + ": " + e.getMessage());
    }
    String unit = d.firstRecipient();
    if (unit == null || unit.isBlank()) {
      throw new TranslationRejectedException(MISSING_UNIT, "Văn bản " + id + " không ghi nơi nhận");
    }
    if (ctx.mappings()
        .resolve(ctx.tenant(), ctx.connector().id(), EntityType.DON_VI, unit, ctx.today())
        .isEmpty()) {
      throw new TranslationRejectedException(
          UNMAPPED_UNIT, "Cơ quan nhận " + unit + " chưa được ánh xạ");
    }
    UUID documentId = ctx.internalId(EntityType.VAN_BAN, id);
    VanBanChiDaoDaNhan payload =
        VanBanChiDaoDaNhan.newBuilder()
            .setDocumentId(documentId)
            .setConnectorCode(ctx.connector().code())
            .setExternalId(id)
            .setDocNumber(d.docNumber())
            .setIssuedDate(d.promulgationDate())
            .setIssuer(d.from().name())
            .setSummary(d.subject())
            .setObjectKey(null)
            .setAggregateVersion(1)
            .build();
    return List.of(new CanonicalEvent(TOPIC, AGGREGATE, documentId, 1, payload, Classification.C1));
  }
}
