package vn.viettel.okrkpi.org.application.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.OrgEvents;
import vn.viettel.okrkpi.org.application.OutboxEvent;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.org.application.port.OutboxPort;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore.PersonRow;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.UuidV7;

/** Thành phần dùng chung của đồng bộ, đối soát: mã hoá trường C2, chỉ mục mù, phát EVT-16 và EVT-21 qua outbox. */
@Component
public class SyncSupport {

  static final String COL_BIRTH = "org.person.birth_date_enc";
  static final String COL_NATIONAL = "org.person.national_id_enc";
  static final String COL_NATIONAL_BIDX = "org.person.national_id_bidx";
  static final String COL_SOURCE = "org.sync_discrepancy.source_value_enc";
  static final String COL_CURRENT = "org.sync_discrepancy.current_value_enc";
  static final String COL_FINGERPRINT = "org.sync_discrepancy.source_fingerprint";
  static final String NOTIFY_TOPIC = "notify.message.requested.v1";

  private final FieldEncryptor crypto;
  private final SyncPersonStore persons;
  private final OutboxPort outbox;
  private final Clock clock;
  final SyncValues values;

  public SyncSupport(FieldEncryptor crypto, SyncPersonStore persons, OutboxPort outbox, ObjectMapper json,
      Clock clock) {
    this.crypto = crypto;
    this.persons = persons;
    this.outbox = outbox;
    this.clock = clock;
    this.values = new SyncValues(json);
  }

  LocalDate today() {
    return LocalDate.now(clock);
  }

  Clock clock() {
    return clock;
  }

  static String normalizeNationalId(String raw) {
    return raw == null ? null : raw.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
  }

  byte[] nationalIdIndex(UUID tenant, String nationalId) {
    return crypto.blindIndex(tenant, COL_NATIONAL_BIDX, normalizeNationalId(nationalId));
  }

  byte[] fingerprint(UUID tenant, String field, byte[] source) {
    String canonical = field + "|" + (source == null ? "" : new String(source, StandardCharsets.UTF_8));
    return crypto.blindIndex(tenant, COL_FINGERPRINT, canonical);
  }

  FieldEncryptor.Encrypted encrypt(UUID tenant, String column, UUID rowId, byte[] plain) {
    return plain == null ? null : crypto.encrypt(tenant, column, rowId, plain);
  }

  byte[] decrypt(UUID tenant, String column, UUID rowId, UUID dekId, byte[] cipher) {
    return cipher == null ? null : crypto.decrypt(tenant, column, rowId, dekId, cipher);
  }

  LocalDate birthDate(UUID tenant, PersonRow p) {
    byte[] plain = decrypt(tenant, COL_BIRTH, p.id(), p.dekId(), p.birthDateEnc());
    return plain == null ? null : LocalDate.parse(new String(plain, StandardCharsets.UTF_8));
  }

  String nationalId(UUID tenant, PersonRow p) {
    byte[] plain = decrypt(tenant, COL_NATIONAL, p.id(), p.dekId(), p.nationalIdEnc());
    return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
  }

  /** EVT-16: trạng thái hiện hành của nhân sự (không có trường C2). */
  void emitPersonChanged(UUID personId, Actor actor) {
    PersonRow p = persons.findById(personId, today())
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy cán bộ."));
    int version = persons.entityVersion(personId);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("personId", personId.toString());
    payload.put("tenantId", actor.tenantId().toString());
    payload.put("sourceVersion", version);
    payload.put("displayName", p.fullName());
    payload.put("isPartyMember", p.partyMember());
    payload.put("status", p.employmentStatus());
    OrgEvents type = OrgEvents.PERSON_CHANGED;
    outbox.append(new OutboxEvent(UuidV7.next(clock), type.topic(), type.eventType(), type.aggregateType(), personId,
        version, actor.tenantId(), actor.actorId(), clock.instant(), actor.traceparent(), "C1", payload));
  }

  /** EVT-21 YeuCauThongBao cho người được giao kiểm tra một dòng lệch (khoá {@code tenant:recipientId}). */
  void emitAssignedNotice(UUID discrepancyId, UUID assigneeId, Actor actor) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("recipientIds", List.of(assigneeId.toString()));
    payload.put("templateCode", "ORG_DOI_SOAT_DUOC_GIAO");
    payload.put("params", "{\"discrepancyId\":\"" + discrepancyId + "\"}");
    payload.put("deepLink", "/quan-tri/dong-bo-nhan-su?lech=" + discrepancyId);
    payload.put("priority", "BINH_THUONG");
    payload.put("aggregateVersion", 0);
    outbox.append(new OutboxEvent(UuidV7.next(clock), NOTIFY_TOPIC, "YeuCauThongBao", "Notification", assigneeId, 0,
        actor.tenantId(), actor.actorId(), clock.instant(), actor.traceparent(), "C1", payload));
  }
}
