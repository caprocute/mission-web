package vn.viettel.okrkpi.docgen.application.render;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;

/**
 * Giải mã giá trị mức C2 trong dữ liệu biểu mẫu (DV-11-02): MOD-06 gửi trường C2 dưới dạng phong bì
 * mã hoá {@code {"c2": "<base64>"}} bằng DEK của tenant, worker chỉ giải mã trong bộ nhớ lúc trộn,
 * không ghi giá trị rõ ra nhật ký, CSDL hay tệp trung gian (TL-04 SEC, suy luận về định dạng).
 *
 * <p>AAD gồm mã biểu mẫu nguồn và tag trường ({@link FormModelBuilder#aad}), nên phong bì của
 * trường này không giải mã được ở trường khác.
 */
public interface SealedValueOpener {

  /** Chưa cấu hình giải mã: biểu mẫu có trường C2 mã hoá không sinh được. */
  SealedValueOpener UNAVAILABLE =
      (tenantId, aad, envelope) -> {
        throw new RejectedDocument("Dịch vụ sinh biểu mẫu chưa được cấu hình giải mã dữ liệu.");
      };

  String open(UUID tenantId, List<String> aad, byte[] envelope);
}
