package vn.viettel.okrkpi.product.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.RecoveryKeySettingApi;
import vn.viettel.okrkpi.product.application.e2ee.EvidenceKeyWrapService;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.RecoveryKeys;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.RecoveryKeys.RecoveryKey;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps.RecoveryRecords;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.infrastructure.e2ee.OrgRecoveryKeyClient;
import vn.viettel.okrkpi.task.api.TaskApi;

/**
 * CORE-07-06: khoá đã bọc của minh chứng C3. Khoá phục hồi tổ chức đọc cùng khoá cấu hình tenant
 * {@value #RECOVERY_KEY} với MOD-02 ({@code {"fingerprints":[…],"recipientId":"…"}}).
 */
@Configuration(proxyBeanMethods = false)
public class EvidenceKeyWrapConfiguration {

  public static final String RECOVERY_KEY = RecoveryKeySettingApi.KEY;
  private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

  @Bean
  JdbcEvidenceKeyWraps evidenceKeyWraps(@Qualifier("productDataSource") DataSource dataSource) {
    return new JdbcEvidenceKeyWraps(JdbcClient.create(dataSource));
  }

  @Bean
  RecoveryKeys evidenceRecoveryKeys(RecoveryKeySettingApi settings) {
    return tenant -> settings.current(tenant).flatMap(EvidenceKeyWrapConfiguration::parse);
  }

  @Bean
  EvidenceKeyWrapService evidenceKeyWrapService(
      EvidenceSubmissionService evidenceSubmissionService,
      JdbcEvidenceKeyWraps evidenceKeyWraps,
      RecoveryKeys evidenceRecoveryKeys,
      ObjectProvider<OrgRecoveryKeyClient> org,
      TaskApi tasks,
      AuditLogger audit) {
    OrgRecoveryKeyClient client = org.getIfAvailable();
    if (client == null) {
      return new EvidenceKeyWrapService(
          evidenceSubmissionService,
          evidenceKeyWraps,
          evidenceRecoveryKeys,
          tasks,
          audit,
          java.time.Clock.systemUTC());
    }
    RecoveryRecords records =
        (tenant, id) ->
            client
                .recovery(tenant, id)
                .map(
                    r ->
                        new RecoveryRecords.Recovery(
                            r.objectType(), r.objectId(), r.newRecipientId(), r.recordedAt()));
    return new EvidenceKeyWrapService(
        evidenceSubmissionService,
        evidenceKeyWraps,
        evidenceRecoveryKeys,
        records,
        tasks,
        audit,
        java.time.Clock.systemUTC());
  }

  static Optional<RecoveryKey> parse(JsonNode v) {
    JsonNode fps = v.path("fingerprints");
    JsonNode recipient = v.path("recipientId");
    if (!fps.isArray() || fps.isEmpty() || !recipient.isTextual()) {
      return Optional.empty();
    }
    List<String> ids = new ArrayList<>();
    for (JsonNode f : fps) {
      if (!f.isTextual() || !HEX64.matcher(f.asText()).matches()) {
        return Optional.empty();
      }
      ids.add(f.asText());
    }
    try {
      return Optional.of(new RecoveryKey(ids, UUID.fromString(recipient.asText())));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
