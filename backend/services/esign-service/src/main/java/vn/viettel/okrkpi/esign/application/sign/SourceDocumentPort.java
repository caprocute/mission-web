package vn.viettel.okrkpi.esign.application.sign;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.esign.domain.sign.SignPurpose;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;

/**
 * Cổng lấy tài liệu cần ký từ module nguồn. Client không bao giờ gửi băm (hợp đồng {@code
 * createEsignRequest}); máy chủ hỏi module sở hữu bản ghi xem bản ghi đã sẵn sàng chưa, ai được ký
 * và băm từng tài liệu. Hợp đồng công khai không có thao tác này nên đi qua cổng nội bộ (suy luận).
 */
public interface SourceDocumentPort {

  /** Rỗng khi bản ghi nguồn không tồn tại trong tenant. */
  Optional<SourceDocuments> fetch(
      UUID tenantId, String sourceModule, UUID sourceRefId, SignPurpose purpose);

  /** Bản ghi nguồn: đã sẵn sàng ký chưa, ai là chủ thể ký, các tài liệu kèm băm. */
  record SourceDocuments(boolean ready, Set<UUID> signers, List<Document> documents) {
    public SourceDocuments {
      signers = Set.copyOf(Objects.requireNonNull(signers, "signers"));
      documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    }
  }

  /** Một tài liệu cần ký. */
  record Document(String kind, String ref, byte[] sha256, SignatureFormat format) {
    public Document {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(ref, "ref");
      Objects.requireNonNull(sha256, "sha256");
      Objects.requireNonNull(format, "format");
      sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
      return sha256.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Document d
          && ref.equals(d.ref)
          && java.util.Arrays.equals(sha256, d.sha256);
    }

    @Override
    public int hashCode() {
      return ref.hashCode();
    }

    @Override
    public String toString() {
      return "Document[" + kind + ", " + ref + "]";
    }
  }
}
