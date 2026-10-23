package vn.viettel.okrkpi.org.application.republish;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.org.application.port.BootstrapPublisher;
import vn.viettel.okrkpi.org.application.port.RepublishSource;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.org.v1.PhanCongDaThayDoi;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * DV-04-08 · TL-03 "Event-carried State Transfer cho dữ liệu tổ chức" · Lệnh {@code org-republish}: đẩy trạng thái
 * hiện hành của mọi thực thể một tenant lên {@code org.bootstrap.v1}.
 * <ul>
 *   <li>Đọc trong một giao dịch {@code REPEATABLE READ} chỉ đọc nên mọi thông điệp thuộc cùng một ảnh chụp; phát
 *       thẳng lên Kafka, không qua outbox để không làm phình bảng outbox. Sự kiện thường phát song song vẫn đi qua
 *       outbox; bên nhận hội tụ nhờ upsert có điều kiện {@code source_version} (suy luận).
 *   <li>Thứ tự: {@code BAT_DAU}, đơn vị, nhân sự, phân công, {@code KET_THUC} (số thực thể theo loại, băm tổng theo
 *       bucket). Thứ tự chỉ được giữ trong một partition; bên nhận biết đã đủ khi đếm khớp {@code KET_THUC}.
 *   <li>Mỗi lần chạy, kể cả chạy thử và chạy lỗi, ghi một sự kiện {@code sys.audit.v1}.
 *   <li>{@code --hashes-only} (DL-12): chỉ phát {@code BAT_DAU} và {@code KET_THUC}, không phát thực thể; bên nhận
 *       so băm bucket của bản sao với {@code KET_THUC} rồi yêu cầu phát lại riêng bucket lệch.
 * </ul>
 * Chỉ dựng ở hồ sơ {@code republish} (xem {@code RepublishConfig}).
 */
public class OrgRepublishService {

  /** Mã hành động kiểm toán. */
  public static final String AUDIT_ACTION = "PHAT_LAI_TO_CHUC";
  /** Đánh dấu đầu loạt. */
  public static final String MARKER_START = "BAT_DAU";
  /** Đánh dấu cuối loạt. */
  public static final String MARKER_END = "KET_THUC";

  private static final Logger LOG = LoggerFactory.getLogger(OrgRepublishService.class);

  private final RepublishSource source;
  private final BootstrapPublisher publisher;
  private final TransactionTemplate snapshotTx;
  private final Clock clock;

  public OrgRepublishService(
      RepublishSource source, BootstrapPublisher publisher, PlatformTransactionManager tm, Clock clock) {
    this.source = source;
    this.publisher = publisher;
    this.clock = clock;
    this.snapshotTx = new TransactionTemplate(tm);
    snapshotTx.setReadOnly(true);
    snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshotTx.setName("org-republish");
  }

  /** Chạy một lần phát lại; tenant phải đã được đặt vào ngữ cảnh giao dịch bởi bên gọi. */
  public RepublishReport run(RepublishRequest req) {
    UUID batchId = UuidV7.next(clock);
    Instant asOf = req.asOf() == null ? clock.instant() : req.asOf();
    Instant started = clock.instant();
    try {
      RepublishReport report = snapshotTx.execute(s -> snapshot(req, batchId, asOf, started));
      audit(req, batchId, "THANH_CONG", report.entities());
      LOG.info("org-republish batch={} tenant={} dryRun={} entities={} messages={} durationMs={}", batchId,
          req.tenant(), req.dryRun(), report.counts(), report.messages(), report.duration().toMillis());
      return report;
    } catch (RuntimeException e) {
      try {
        audit(req, batchId, "LOI", -1);
      } catch (RuntimeException auditError) {
        e.addSuppressed(auditError);
      }
      throw e;
    }
  }

  private RepublishReport snapshot(RepublishRequest req, UUID batchId, Instant asOf, Instant started) {
    Buckets.Accumulator acc = new Buckets.Accumulator();
    long[] seq = {0};
    Set<Integer> scope = req.buckets();
    if (!req.dryRun()) {
      publisher.publish(req.tenant(), batchId, 0, marker(batchId, seq[0]++, MARKER_START, false, null));
    }
    LocalDate asOfDate = LocalDate.ofInstant(asOf, clock.getZone());
    for (EntityKind kind : req.entities()) {
      switch (kind) {
        case ORG_UNIT -> source.units(asOf, asOfDate, (DonViDaThayDoi u) -> {
          if (scope.contains(Buckets.of(u.getUnitId()))) {
            acc.add(kind, u.getUnitId(), u.getValidFrom(), u.getSourceVersion());
            emit(req, batchId, seq, u.getUnitId(), u.getSourceVersion(), u);
          }
        });
        case PERSON -> source.persons(asOf, asOfDate, (NhanSuDaThayDoi p) -> {
          if (scope.contains(Buckets.of(p.getPersonId()))) {
            acc.add(kind, p.getPersonId(), null, p.getSourceVersion());
            emit(req, batchId, seq, p.getPersonId(), p.getSourceVersion(), p);
          }
        });
        case ASSIGNMENT -> source.assignments(asOf, (PhanCongDaThayDoi a) -> {
          if (scope.contains(Buckets.of(a.getAssignmentId()))) {
            acc.add(kind, a.getAssignmentId(), a.getValidFrom(), a.getSourceVersion());
            emit(req, batchId, seq, a.getAssignmentId(), a.getSourceVersion(), a);
          }
        });
        default -> throw new IllegalStateException(kind.name());
      }
    }
    RepublishReport partial = new RepublishReport(batchId, asOf, acc.counts(req.entities()), acc.hashes(scope), 0,
        Duration.ZERO);
    if (!req.dryRun()) {
      OrgBootstrap end = marker(batchId, seq[0]++, MARKER_END, true, partial);
      publisher.publish(req.tenant(), batchId, 0, end);
      publisher.flush();
    }
    return new RepublishReport(batchId, asOf, partial.counts(), partial.bucketHashes(), seq[0],
        Duration.between(started, clock.instant()));
  }

  private void emit(RepublishRequest req, UUID batchId, long[] seq, UUID id, long sourceVersion, Object entity) {
    if (req.dryRun() || req.hashesOnly()) {
      return;
    }
    OrgBootstrap m = OrgBootstrap.newBuilder().setBatchId(batchId).setSeq(seq[0]++).setIsLast(false)
        .setEntity(entity).setMarker(null).setEntityCounts(null).setBucketHashes(null).build();
    publisher.publish(req.tenant(), id, sourceVersion, m);
  }

  private static OrgBootstrap marker(UUID batchId, long seq, String marker, boolean last, RepublishReport totals) {
    return OrgBootstrap.newBuilder().setBatchId(batchId).setSeq(seq).setIsLast(last).setEntity(null)
        .setMarker(marker)
        .setEntityCounts(totals == null ? null : new java.util.LinkedHashMap<>(totals.counts()))
        .setBucketHashes(totals == null ? null : new java.util.LinkedHashMap<>(totals.bucketHashes()))
        .build();
  }

  private void audit(RepublishRequest req, UUID batchId, String outcome, long entities) {
    String scope = "entities=" + req.entities() + ";buckets=" + (req.allBuckets() ? "ALL" : req.buckets().toString())
        + ";dryRun=" + req.dryRun() + ";count=" + entities + ";operator=" + req.operator();
    AuditEvent event = AuditEvent.newBuilder()
        .setAction(AUDIT_ACTION)
        .setObjectType("ORG_BOOTSTRAP")
        .setObjectId(batchId.toString())
        .setOutcome(outcome)
        .setPurpose(scope)
        .setActorAssignmentId(null)
        .setActorRole(null)
        .setSystem(true)
        .setTargets(null)
        .build();
    publisher.audit(req.tenant(), batchId, actorId(req.operator()), event);
  }

  /** Người chạy là mã tài khoản thì ghi vào tiêu đề {@code actorid}; tên khác chỉ nằm trong phạm vi kiểm toán. */
  private static UUID actorId(String operator) {
    try {
      return UUID.fromString(operator);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
