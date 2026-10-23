package vn.viettel.okrkpi.integ.infrastructure.voffice;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;

/**
 * Bước phát EVT-24 {@code integ.document.received.v1} dùng chung cho mọi hệ điều hành văn bản
 * (vOffice DV-13-03, iOffice và hãng khác DV-13-04), để văn bản đến hội tụ về một cấu trúc. Mã văn
 * bản nội bộ ổn định qua {@code mapping_xref} {@code VAN_BAN} theo mã của hệ nguồn, phiên bản của
 * hệ nguồn là {@code aggregateVersion}. Đơn vị nhận phải đã ánh xạ ({@code DON_VI}); chưa ánh xạ
 * thì từ chối không thử lại để quản trị tenant xử lý. Tệp đính kèm chưa chuyển sang
 * evidence-service nên {@code objectKey} để trống.
 */
public final class DirectiveEvents {

  public static final String TOPIC = "integ.document.received.v1";
  public static final String AGGREGATE = "VAN_BAN";
  public static final String UNMAPPED_UNIT = "DON_VI_CHUA_ANH_XA";
  public static final String MISSING_UNIT = "THIEU_DON_VI_NHAN";

  private DirectiveEvents() {}

  /** Đơn vị nhận đã ánh xạ trong ngữ cảnh của kết nối. */
  public static boolean mapped(TranslationContext ctx, String unitId) {
    return unitId != null
        && !unitId.isBlank()
        && ctx.mappings()
            .resolve(ctx.tenant(), ctx.connector().id(), EntityType.DON_VI, unitId, ctx.today())
            .isPresent();
  }

  public static List<CanonicalEvent> emit(
      TranslationContext ctx, String externalId, int version, Directive d) {
    if (d.recipientUnitId() == null || d.recipientUnitId().isBlank()) {
      throw new TranslationRejectedException(
          MISSING_UNIT, "Văn bản " + externalId + " không ghi đơn vị nhận");
    }
    if (!mapped(ctx, d.recipientUnitId())) {
      throw new TranslationRejectedException(
          UNMAPPED_UNIT, "Đơn vị nhận " + d.recipientUnitId() + " chưa được ánh xạ");
    }
    UUID documentId = ctx.internalId(EntityType.VAN_BAN, externalId);
    VanBanChiDaoDaNhan payload =
        VanBanChiDaoDaNhan.newBuilder()
            .setDocumentId(documentId)
            .setConnectorCode(ctx.connector().code())
            .setExternalId(externalId)
            .setDocNumber(d.docNumber())
            .setIssuedDate(d.issuedDate())
            .setIssuer(d.issuer())
            .setSummary(d.summary())
            .setObjectKey(null)
            .setAggregateVersion(version)
            .build();
    return List.of(
        new CanonicalEvent(TOPIC, AGGREGATE, documentId, version, payload, Classification.C1));
  }
}
