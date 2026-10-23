package vn.viettel.okrkpi.integ.infrastructure.edxml;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Gói tin EdXML (QĐ 28/2018/QĐ-TTg, NĐ 30/2020) ở mức nền tảng cần: tiêu đề văn bản, cơ quan gửi và
 * nhận theo mã định danh cơ quan, tệp đính kèm, và việc gói có chữ ký số. Dùng chung cho vOffice
 * (DV-13-03) và Trục liên thông văn bản quốc gia (DV-13-05). Không in nội dung ra nhật ký.
 */
public record EdxmlDocument(
    String documentId,
    Organ from,
    List<Organ> to,
    String codeNumber,
    String codeNotation,
    String place,
    LocalDate promulgationDate,
    String documentType,
    String subject,
    String signerName,
    String signerPosition,
    List<Attachment> attachments,
    boolean signed) {

  public EdxmlDocument {
    to = to == null ? List.of() : List.copyOf(to);
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
  }

  /** Cơ quan theo mã định danh (QĐ 20/2020/QĐ-TTg) và tên. */
  public record Organ(String id, String name) {}

  /** Tệp đính kèm, nội dung đã giải mã base64. */
  public record Attachment(String contentId, String name, String contentType, byte[] content) {

    public Attachment {
      Objects.requireNonNull(content, "content");
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Attachment a
          && Objects.equals(contentId, a.contentId)
          && Objects.equals(name, a.name)
          && Objects.equals(contentType, a.contentType)
          && Arrays.equals(content, a.content);
    }

    @Override
    public int hashCode() {
      return Objects.hash(contentId, name, contentType, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
      return "Attachment[" + name + ", " + content.length + " byte]";
    }
  }

  /** Số, ký hiệu văn bản dạng "15/CV-VPTU". */
  public String docNumber() {
    return codeNotation == null ? codeNumber : codeNumber + "/" + codeNotation;
  }

  /** Mã cơ quan nhận đầu tiên, {@code null} khi gói không ghi nơi nhận. */
  public String firstRecipient() {
    return to.isEmpty() ? null : to.get(0).id();
  }

  @Override
  public String toString() {
    return "EdxmlDocument[" + documentId + ", " + attachments.size() + " tệp]";
  }
}
