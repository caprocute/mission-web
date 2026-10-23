package vn.viettel.okrkpi.integ.infrastructure.voffice;

import java.time.LocalDate;

/**
 * Văn bản chỉ đạo đã đọc từ vOffice (JSON hoặc EdXML) trước khi dịch sang mô hình chuẩn; chỉ giữ
 * trong bộ nhớ, không ghi nhật ký.
 */
record Directive(
    String docNumber,
    LocalDate issuedDate,
    String issuer,
    String summary,
    String recipientUnitId) {}
