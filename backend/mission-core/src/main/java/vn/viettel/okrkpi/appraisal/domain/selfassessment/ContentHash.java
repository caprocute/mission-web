package vn.viettel.okrkpi.appraisal.domain.selfassessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.UUID;

/**
 * Băm SHA-256 dạng chuẩn hoá của phiếu đã chốt, là nội dung được ký số (BR-09-15). Dạng chuẩn hoá
 * ghép từng trường theo thứ tự cố định, mỗi trường {@code tên=độ dài:giá trị;} nên không có hai
 * phiếu khác nhau cùng một chuỗi; tiêu chí sắp theo mã (suy luận, TL-03 chỉ ghi "dạng chuẩn hoá").
 */
public final class ContentHash {

  private ContentHash() {}

  public static byte[] of(UUID profileId, int versionNo, SelfAssessmentForm form) {
    StringBuilder b = new StringBuilder();
    field(b, "profile", profileId.toString());
    field(b, "version", Integer.toString(versionNo));
    form.criteria().stream()
        .sorted(Comparator.comparing(SelfAssessmentForm.Criterion::code))
        .forEach(c -> field(b, "criterion", c.code() + "=" + c.met()));
    field(b, "selfRating", form.selfRating() == null ? null : form.selfRating().name());
    field(b, "breakthrough", form.breakthrough());
    form.breakthroughEvidenceIds().stream()
        .sorted()
        .forEach(id -> field(b, "evidence", id.toString()));
    field(b, "reason", form.reason());
    field(b, "content", form.content());
    field(b, "strengths", form.strengths());
    field(b, "weaknesses", form.weaknesses());
    field(b, "remedialPlan", form.remedialPlan());
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(b.toString().getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void field(StringBuilder b, String name, String value) {
    String v = value == null ? "" : value;
    b.append(name).append('=').append(v.length()).append(':').append(v).append(';');
  }
}
