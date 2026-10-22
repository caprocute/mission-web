package vn.viettel.okrkpi.product.application.evidence.port;

import java.util.UUID;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository.SealedComment;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mã hoá phong bì nhận xét nghiệm thu C2 ({@code product.acceptance.comment_enc}, TL-03); AAD gồm
 * tenant, cột và mã dòng nên bản mã không chép được sang dòng khác.
 */
public interface CommentSealer {

  /** {@code null} khi {@code plain} rỗng. */
  SealedComment seal(TenantId tenant, UUID acceptanceId, String plain);
}
