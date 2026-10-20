package vn.viettel.okrkpi.shared.inbox;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NEN-012 · Tác vụ dọn bảng {@code <schema>.inbox_processed} quá hạn (mặc định 14 ngày) theo từng
 * lô qua hàm {@code <schema>.fn_purge_inbox_processed} của NEN-032 (TL-03 L4290–4305).
 *
 * <p>Dùng khoá tư vấn giao dịch {@code pg_try_advisory_xact_lock(hashtext(...))} để khi nhiều pod
 * cùng bật lịch thì tại một thời điểm chỉ một pod thực thi xoá trên cùng schema.
 */
public class InboxPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(InboxPurgeJob.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactionTemplate;
  private final String schema;
  private final InboxProperties.Purge config;
  private final String lockKey;
  private final String lockSql;
  private final String purgeSql;

  public InboxPurgeJob(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      String schema,
      InboxProperties.Purge config) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.schema = InboxMessageProcessor.requireSchema(schema);
    this.config = config == null ? new InboxProperties.Purge(null, null, null, null, null) : config;
    this.lockKey = this.schema + ".inbox_processed.purge";
    this.lockSql = "SELECT pg_try_advisory_xact_lock(hashtext(?))";
    this.purgeSql = "SELECT " + this.schema + ".fn_purge_inbox_processed(?::interval, ?)";
  }

  /**
   * Chạy dọn {@code inbox_processed} theo lô cho đến khi số dòng xoá trong lô nhỏ hơn {@code
   * batchSize} hoặc đạt {@code maxBatchesPerRun}.
   *
   * @return tổng số dòng đã xoá trong lần chạy này
   */
  @Scheduled(cron = "${okrkpi.inbox.purge.cron:0 15 3 * * *}")
  public int purgeExpired() {
    if (!Boolean.TRUE.equals(config.enabled())) {
      return 0;
    }
    int totalDeleted = 0;
    int maxBatches = config.maxBatchesPerRun();
    int batchSize = config.batchSize();
    for (int batch = 0; batch < maxBatches; batch++) {
      Integer deleted =
          transactionTemplate.execute(
              status -> {
                Boolean locked = jdbc.queryForObject(lockSql, Boolean.class, lockKey);
                if (!Boolean.TRUE.equals(locked)) {
                  return -1;
                }
                Integer count =
                    jdbc.queryForObject(
                        purgeSql, Integer.class, config.retentionInterval(), batchSize);
                return count == null ? 0 : count;
              });
      if (deleted == null || deleted < 0) {
        log.debug("Bỏ qua lượt dọn {}.inbox_processed vì pod khác đang giữ khoá", schema);
        break;
      }
      totalDeleted += deleted;
      if (deleted < batchSize) {
        break;
      }
    }
    if (totalDeleted > 0) {
      log.info(
          "Đã dọn {} dòng quá hạn ({}) khỏi {}.inbox_processed",
          totalDeleted,
          config.retentionInterval(),
          schema);
    }
    return totalDeleted;
  }
}
