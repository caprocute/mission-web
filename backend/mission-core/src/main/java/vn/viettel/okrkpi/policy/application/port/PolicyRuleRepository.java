package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc và ghi quy tắc CEL kèm khoá lạc quan {@code row_version} (CORE-05-04, SCR-1501). */
public interface PolicyRuleRepository {

  /** Quy tắc đã lưu cùng {@code row_version} dùng làm ETag. */
  record StoredRule(PolicyRule rule, long rowVersion) {}

  List<StoredRule> storedRules(TenantId tenant, UUID versionId);

  Optional<StoredRule> storedRule(TenantId tenant, UUID versionId, String ruleKey);

  /** Ghi đè nội dung quy tắc khi {@code row_version} còn bằng {@code expected}. */
  boolean replaceRule(TenantId tenant, PolicyRule rule, long expected, UUID actor);

  /** Thêm quy tắc mới vào phiên bản nháp. */
  void appendRule(TenantId tenant, PolicyRule rule, UUID actor);
}
