package vn.viettel.okrkpi.esign.infrastructure.tsa;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.esign.application.seal.TimestampAuthority;
import vn.viettel.okrkpi.esign.infrastructure.provider.mysign.ObjectStorage;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Job gia hạn dấu thời gian lưu trữ (DV-10-03, TL-01 "Ký số tại mốc pháp lý"): hằng năm duyệt mọi
 * tài liệu PAdES-LTA đã ký, tài liệu có chứng thư TSA của dấu thời gian mới nhất hết hạn trong
 * khoảng {@code renewBefore} được thêm một dấu thời gian tài liệu mới, ghi thành đối tượng mới và
 * {@code sign_item.signed_object_key} trỏ sang đối tượng đó (đối tượng cũ giữ nguyên). Kết quả ghi
 * vào nhật ký job (suy luận về nơi ghi).
 */
public class ArchiveTimestampRenewalJob {

  private static final Logger log = LoggerFactory.getLogger(ArchiveTimestampRenewalJob.class);
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  /** Kết quả một lượt chạy. */
  public record Result(int scanned, int renewed, int skipped, int failed) {}

  private record Item(UUID id, UUID tenantId, String key) {}

  private final JdbcClient jdbc;
  private final ObjectStorage storage;
  private final TimestampAuthority tsa;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Duration renewBefore;
  private final int batchSize;

  public ArchiveTimestampRenewalJob(
      JdbcClient jdbc,
      ObjectStorage storage,
      TimestampAuthority tsa,
      TransactionTemplate tx,
      Clock clock,
      Duration renewBefore,
      int batchSize) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.tsa = tsa;
    this.tx = tx;
    this.clock = clock;
    this.renewBefore = renewBefore;
    this.batchSize = batchSize;
  }

  @Scheduled(cron = "${okrkpi.esign.archive-timestamp.cron:0 0 2 15 1 *}", zone = "UTC")
  public void scheduled() {
    run();
  }

  public Result run() {
    Instant now = clock.instant();
    Instant threshold = now.plus(renewBefore);
    int scanned = 0;
    int renewed = 0;
    int skipped = 0;
    int failed = 0;
    UUID after = null;
    while (true) {
      List<Item> page = page(after);
      if (page.isEmpty()) {
        break;
      }
      for (Item item : page) {
        scanned++;
        try {
          java.util.concurrent.atomic.AtomicBoolean done =
              new java.util.concurrent.atomic.AtomicBoolean();
          TenantContext.run(
              TenantId.of(item.tenantId()), () -> done.set(renew(item, now, threshold)));
          if (done.get()) {
            renewed++;
          } else {
            skipped++;
          }
        } catch (RuntimeException e) {
          failed++;
          log.warn("Gia hạn dấu thời gian tài liệu {} lỗi: {}", item.id(), e.getMessage());
        }
      }
      after = page.get(page.size() - 1).id();
    }
    Result result = new Result(scanned, renewed, skipped, failed);
    log.info("Job gia hạn dấu thời gian lưu trữ: {}", result);
    return result;
  }

  private List<Item> page(UUID after) {
    return jdbc.sql("SELECT id, tenant_id, signed_object_key FROM esign.fn_tai_lieu_luu_tru(?, ?)")
        .param(after)
        .param(batchSize)
        .query(
            (rs, i) ->
                new Item(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("signed_object_key")))
        .list();
  }

  private boolean renew(Item item, Instant now, Instant threshold) {
    Optional<byte[]> pdf = storage.get(item.key());
    if (pdf.isEmpty()) {
      log.warn("Không thấy tệp đã ký của tài liệu {}", item.id());
      return false;
    }
    Optional<Instant> expiry = ArchiveTimestamps.latestTsaExpiry(pdf.get());
    if (expiry.isPresent() && expiry.get().isAfter(threshold)) {
      return false;
    }
    Calendar date = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    date.setTimeInMillis(now.toEpochMilli());
    byte[] renewed = ArchiveTimestamps.addDocumentTimestamp(pdf.get(), tsa, date);
    String key = renewedKey(item.key(), now);
    storage.put(key, renewed, "application/pdf");
    Integer updated =
        tx.execute(
            s ->
                jdbc.sql(
                        "UPDATE esign.sign_item SET signed_object_key = ? "
                            + "WHERE id = ? AND signed_object_key = ?")
                    .param(key)
                    .param(item.id())
                    .param(item.key())
                    .update());
    return updated != null && updated == 1;
  }

  /** {@code a/b.pdf} → {@code a/b.lta-20270115.pdf}; lần gia hạn sau thay hậu tố cũ. */
  static String renewedKey(String key, Instant now) {
    String base = key.endsWith(".pdf") ? key.substring(0, key.length() - 4) : key;
    base = base.replaceFirst("\\.lta-\\d{8}$", "");
    return base + ".lta-" + DAY.format(now) + ".pdf";
  }
}
