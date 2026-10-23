package vn.viettel.okrkpi.integ.infrastructure.truc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import vn.viettel.okrkpi.integ.application.EdxmlTransferService;
import vn.viettel.okrkpi.integ.application.EdxmlTransferService.Request;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Attachment;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument.Organ;

/**
 * Dựng gói EdXML cho văn bản gửi ra: cơ quan gửi và nhận lấy từ tham số {@code from}, {@code to}
 * của địa chỉ kết nối nơi nhận ({@link XRoadEndpoint}); {@code DocumentId} là mã lần gửi của MOD-06
 * để Trục chống trùng. Gói con đã được MOD-06 ký và mã hoá nên chỉ đóng vào phần đính kèm.
 */
public class TrucEdxmlComposer implements EdxmlTransferService.Composer {

  private final Clock clock;

  public TrucEdxmlComposer(Clock clock) {
    this.clock = clock;
  }

  @Override
  public byte[] compose(Connector destination, Request request) {
    XRoadEndpoint ep;
    try {
      ep = XRoadEndpoint.of(destination);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (ep.fromId() == null || ep.toId() == null) {
      throw new IllegalStateException(
          "Kết nối " + destination.code() + " thiếu mã định danh cơ quan gửi hoặc nhận");
    }
    String number = request.documentNumber();
    String codeNumber = number;
    String notation = null;
    if (number != null && number.indexOf('/') > 0) {
      codeNumber = number.substring(0, number.indexOf('/'));
      notation = number.substring(number.indexOf('/') + 1);
    }
    List<Attachment> files = new ArrayList<>();
    int i = 0;
    for (EdxmlTransferService.Attachment a : request.attachments()) {
      files.add(
          new Attachment(
              "tep-" + (++i),
              a.name(),
              a.contentType() == null ? "application/octet-stream" : a.contentType(),
              a.content()));
    }
    EdxmlDocument d =
        new EdxmlDocument(
            request.transferId().toString(),
            new Organ(ep.fromId(), ep.fromName() == null ? ep.fromId() : ep.fromName()),
            List.of(new Organ(ep.toId(), ep.toName())),
            codeNumber == null || codeNumber.isBlank()
                ? request.transferId().toString()
                : codeNumber,
            notation,
            null,
            LocalDate.now(clock),
            "Hồ sơ cán bộ",
            request.subject(),
            null,
            null,
            files,
            false);
    return Edxml.build(d);
  }
}
