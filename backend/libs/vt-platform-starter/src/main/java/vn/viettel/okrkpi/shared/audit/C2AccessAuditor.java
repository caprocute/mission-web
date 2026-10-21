package vn.viettel.okrkpi.shared.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.crypto.DecryptListener;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.sys.v1.AuditEvent;
import vn.vtokr.sys.v1.AuditTarget;

/**
 * CORE-00-05 · ADR-06, ADR-09, BR-12-10, BR-16-04 · Nhật ký truy cập dữ liệu C2.
 *
 * <p>Mỗi lần {@code FieldEncryptor} giải mã (hoặc use case {@link C2Access} trả tệp C2), đối tượng
 * được thêm vào bộ gom của giao dịch đang mở; ngay trước khi giao dịch commit, bộ gom phát sự kiện
 * {@code sys.audit.v1} ({@code action = XEM_C2}) qua {@link OutboxWriter}, chia lô theo {@link
 * #maxTargetsPerEvent} để không làm phình outbox. SVC-09 nối sự kiện vào sổ cái.
 *
 * <ul>
 *   <li>Giao dịch ghi: dòng outbox nằm trong chính giao dịch đọc.
 *   <li>Giao dịch chỉ đọc: PostgreSQL từ chối INSERT trong {@code READ ONLY}, nên ghi vết bằng giao
 *       dịch riêng ({@code REQUIRES_NEW}) ngay trước commit (suy luận, TL chưa mô tả).
 *   <li>Không có giao dịch: ghi ngay bằng giao dịch riêng trước khi trả dữ liệu.
 * </ul>
 *
 * <p>Ghi vết lỗi thì ngoại lệ lan ra người gọi (giao dịch đọc rollback), dữ liệu C2 không được trả:
 * thà không hiển thị còn hơn hiển thị không vết. Sự kiện và nhật ký vận hành không bao giờ mang bản
 * rõ hay bản mã, chỉ toạ độ cột và mã dòng.
 */
public class C2AccessAuditor implements DecryptListener {

  /** Hành động kiểm toán khi xem dữ liệu C2. */
  public static final String ACTION = "XEM_C2";

  /** Loại đối tượng của sự kiện khi một sự kiện gom nhiều loại. */
  public static final String MULTIPLE = "NHIEU_DOI_TUONG";

  /** Toạ độ ghi thay {@code schema.bảng.cột} khi đọc tệp C2. */
  public static final String FILE_COLUMN = "FILE";

  static final String AGGREGATE_TYPE = "C2Access";
  static final String OUTCOME = "THANH_CONG";

  private final OutboxWriter outbox;
  private final TransactionTemplate separateTx;
  private final C2ActorResolver actors;
  private final int maxTargetsPerEvent;

  public C2AccessAuditor(
      OutboxWriter outbox,
      PlatformTransactionManager transactionManager,
      C2ActorResolver actors,
      int maxTargetsPerEvent) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.separateTx = new TransactionTemplate(transactionManager);
    this.separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.actors = Objects.requireNonNull(actors, "actors");
    if (maxTargetsPerEvent < 1) {
      throw new IllegalArgumentException("maxTargetsPerEvent phải >= 1");
    }
    this.maxTargetsPerEvent = maxTargetsPerEvent;
  }

  /** Điểm móc của {@code FieldEncryptor}: AAD là {@code [schema.bảng.cột, mã dòng]}. */
  @Override
  public void onDecrypt(UUID tenantId, DekPurpose purpose, List<String> aadParts) {
    String column = aadParts == null || aadParts.isEmpty() ? "?" : aadParts.get(0);
    String objectId = aadParts == null || aadParts.size() < 2 ? "?" : aadParts.get(1);
    record(
        TenantId.of(tenantId),
        new Target(
            objectTypeOf(column), objectId, column, purpose == null ? null : purpose.name()));
  }

  /**
   * Ghi một lần đọc tệp hoặc dữ liệu C2 không qua {@code FieldEncryptor} (dùng cho {@link
   * C2Access}).
   */
  public void recordAccess(TenantId tenant, String objectType, String objectId, String column) {
    record(tenant, new Target(objectType, objectId, column, null));
  }

  void record(TenantId tenant, Target target) {
    Objects.requireNonNull(tenant, "tenant");
    C2Actor actor = actors.current();
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      flushSeparately(tenant, actor, List.of(target));
      return;
    }
    Batch batch = (Batch) TransactionSynchronizationManager.getResource(this);
    if (batch == null) {
      batch = new Batch(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
      TransactionSynchronizationManager.bindResource(this, batch);
      TransactionSynchronizationManager.registerSynchronization(batch);
    }
    batch.add(tenant, actor, target);
  }

  private void flushSeparately(TenantId tenant, C2Actor actor, List<Target> targets) {
    try {
      Runnable write = () -> separateTx.executeWithoutResult(s -> emit(tenant, actor, targets));
      if (TenantContext.current().filter(tenant::equals).isPresent()) {
        write.run();
      } else {
        TenantContext.run(tenant, write);
      }
    } catch (RuntimeException e) {
      throw new C2AccessAuditException("Không ghi được nhật ký truy cập dữ liệu C2", e);
    }
  }

  private void emit(TenantId tenant, C2Actor actor, List<Target> targets) {
    for (int from = 0; from < targets.size(); from += maxTargetsPerEvent) {
      List<Target> chunk =
          targets.subList(from, Math.min(targets.size(), from + maxTargetsPerEvent));
      outbox.append(envelope(tenant, actor, chunk));
    }
  }

  private static EventEnvelope envelope(TenantId tenant, C2Actor actor, List<Target> chunk) {
    Set<String> types = new LinkedHashSet<>();
    List<AuditTarget> items = new ArrayList<>(chunk.size());
    for (Target t : chunk) {
      types.add(t.objectType());
      items.add(
          AuditTarget.newBuilder()
              .setObjectType(t.objectType())
              .setObjectId(t.objectId())
              .setColumn(t.column())
              .setDekPurpose(t.dekPurpose())
              .setSelf(selfOf(actor, t))
              .build());
    }
    boolean single = chunk.size() == 1;
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction(ACTION)
            .setObjectType(types.size() == 1 ? types.iterator().next() : MULTIPLE)
            .setObjectId(single ? chunk.getFirst().objectId() : null)
            .setOutcome(OUTCOME)
            .setPurpose(null)
            .setActorAssignmentId(actor.assignmentId())
            .setActorRole(actor.role())
            .setSystem(actor.system())
            .setTargets(items)
            .build();
    UUID eventId = Ids.newUuidV7();
    return new EventEnvelope(
        eventId,
        EventTopics.SYS_AUDIT_V1,
        tenant,
        AGGREGATE_TYPE,
        eventId,
        0L,
        AuditEvent.class.getSimpleName(),
        payload,
        Classification.C1,
        actor.actorId(),
        null,
        null);
  }

  /**
   * Chỉ biết chắc "của chính mình" khi mã đối tượng là mã người xem (suy luận); còn lại để trống.
   */
  private static Boolean selfOf(C2Actor actor, Target t) {
    if (actor.actorId() != null && actor.actorId().value().toString().equals(t.objectId())) {
      return Boolean.TRUE;
    }
    return null;
  }

  /** Loại đối tượng suy từ tên bảng trong {@code schema.bảng.cột}, viết hoa. */
  static String objectTypeOf(String column) {
    String[] parts = column.split("\\.");
    String table = parts.length >= 2 ? parts[parts.length - 2] : parts[0];
    return table.toUpperCase(java.util.Locale.ROOT);
  }

  record Target(String objectType, String objectId, String column, String dekPurpose) {}

  private record Key(TenantId tenant, C2Actor actor) {}

  /** Bộ gom một giao dịch; phát ngay trước commit, gỡ khỏi luồng khi giao dịch kết thúc. */
  private final class Batch implements TransactionSynchronization {

    private final boolean readOnly;
    private final Map<Key, List<Target>> targets = new LinkedHashMap<>();

    Batch(boolean readOnly) {
      this.readOnly = readOnly;
    }

    void add(TenantId tenant, C2Actor actor, Target target) {
      targets.computeIfAbsent(new Key(tenant, actor), k -> new ArrayList<>()).add(target);
    }

    @Override
    public void beforeCommit(boolean transactionReadOnly) {
      for (Map.Entry<Key, List<Target>> e : targets.entrySet()) {
        if (readOnly || transactionReadOnly) {
          flushSeparately(e.getKey().tenant(), e.getKey().actor(), e.getValue());
        } else {
          try {
            emit(e.getKey().tenant(), e.getKey().actor(), e.getValue());
          } catch (RuntimeException ex) {
            throw new C2AccessAuditException("Không ghi được nhật ký truy cập dữ liệu C2", ex);
          }
        }
      }
      targets.clear();
    }

    @Override
    public void afterCompletion(int status) {
      targets.clear();
      TransactionSynchronizationManager.unbindResourceIfPossible(C2AccessAuditor.this);
    }
  }
}
