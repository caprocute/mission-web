package vn.viettel.okrkpi.integ.infrastructure.voffice;

import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument;

/**
 * Đọc gói EdXML của vOffice (NĐ 30/2020) thành {@link Directive} qua bộ phân tích dùng chung {@link
 * Edxml} (DV-13-05): số, ký hiệu, ngày ban hành, cơ quan ban hành, đơn vị nhận đầu tiên, trích yếu.
 * vOffice giữ trần 2 MB cho mỗi gói.
 */
final class EdxmlParser {

  static final int MAX_BYTES = 2 * 1024 * 1024;

  private EdxmlParser() {}

  static Directive parse(byte[] xml) {
    EdxmlDocument d = Edxml.parse(xml, MAX_BYTES);
    return new Directive(
        d.docNumber(), d.promulgationDate(), d.from().name(), d.subject(), d.firstRecipient());
  }
}
