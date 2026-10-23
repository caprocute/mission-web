package vn.viettel.okrkpi.org.infrastructure.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import vn.viettel.okrkpi.org.application.e2ee.PublicKeyDirectoryService.KeyView;
import vn.viettel.okrkpi.org.application.port.DirectorySigner.VerificationKey;
import vn.viettel.okrkpi.org.application.port.PublicKeyStore.StoredKey;
import vn.viettel.okrkpi.org.application.port.RecoveryKeyStore.StoredRecovery;
import vn.viettel.okrkpi.org.application.port.RecoveryKeyStore.StoredRecoveryKey;
import vn.viettel.okrkpi.org.infrastructure.web.model.DirectoryKey;
import vn.viettel.okrkpi.org.infrastructure.web.model.DirectorySignature;
import vn.viettel.okrkpi.org.infrastructure.web.model.PersonPublicKey;
import vn.viettel.okrkpi.org.infrastructure.web.model.PublicKeyAlg;
import vn.viettel.okrkpi.org.infrastructure.web.model.RecoveryKey;
import vn.viettel.okrkpi.org.infrastructure.web.model.RecoveryObjectType;
import vn.viettel.okrkpi.org.infrastructure.web.model.RecoveryRecord;

/** Ánh xạ danh bạ khoá công khai, khoá phục hồi và nhật ký phục hồi (CORE-07-06) sang mô hình sinh từ hợp đồng. */
final class E2eeWebMapper {

  private E2eeWebMapper() {
  }

  static PersonPublicKey toDto(KeyView v) {
    StoredKey k = v.key();
    PersonPublicKey dto = new PersonPublicKey();
    dto.setId(k.id());
    dto.setPersonId(k.personId());
    dto.setKeyAlg(PublicKeyAlg.fromValue(k.keyAlg()));
    dto.setPublicKeySpki(Base64.getEncoder().encodeToString(k.spki()));
    dto.setFingerprint(HexFormat.of().formatHex(k.fingerprint()));
    dto.setSource(PersonPublicKey.SourceEnum.fromValue(k.source()));
    dto.setStatus(PersonPublicKey.StatusEnum.fromValue(v.status()));
    dto.setValidFrom(utc(k.validFrom()));
    dto.setValidTo(utc(k.validTo()));
    dto.setSignature(signature(k.sigKeyVersion(), k.signature(), k.signedAt()));
    return dto;
  }

  static RecoveryKey toDto(StoredRecoveryKey k) {
    RecoveryKey dto = new RecoveryKey();
    dto.setId(k.id());
    dto.setKeyAlg(RecoveryKey.KeyAlgEnum.fromValue(k.keyAlg()));
    dto.setPublicKeySpki(Base64.getEncoder().encodeToString(k.spki()));
    dto.setFingerprint(HexFormat.of().formatHex(k.fingerprint()));
    dto.setThreshold(k.threshold());
    dto.setCustodianIds(new ArrayList<>(k.custodianIds()));
    dto.setCeremonyRef(k.ceremonyRef());
    dto.setStatus(RecoveryKey.StatusEnum.fromValue(k.status()));
    dto.setValidFrom(utc(k.validFrom()));
    dto.setValidTo(utc(k.validTo()));
    dto.setSignature(signature(k.sigKeyVersion(), k.signature(), k.signedAt()));
    return dto;
  }

  static RecoveryRecord toDto(StoredRecovery r) {
    RecoveryRecord dto = new RecoveryRecord();
    dto.setId(r.id());
    dto.setRecoveryKeyId(r.recoveryKeyId());
    dto.setObjectType(RecoveryObjectType.fromValue(r.objectType()));
    dto.setObjectId(r.objectId());
    dto.setNewRecipientId(r.newRecipientId());
    dto.setNewKeyFingerprint(HexFormat.of().formatHex(r.newKeyFingerprint()));
    dto.setCustodianIds(new ArrayList<>(r.custodianIds()));
    dto.setReason(r.reason());
    dto.setCeremonyRef(r.ceremonyRef());
    dto.setPerformedAt(utc(r.performedAt()));
    dto.setRecordedBy(r.recordedBy());
    dto.setRecordedAt(utc(r.recordedAt()));
    return dto;
  }

  static DirectoryKey toDto(VerificationKey k) {
    DirectoryKey dto = new DirectoryKey();
    dto.setKeyVersion(k.keyVersion());
    dto.setPublicKeyPem(k.publicKeyPem());
    return dto;
  }

  static Instant instant(OffsetDateTime t) {
    return t == null ? null : t.toInstant();
  }

  private static DirectorySignature signature(int version, byte[] value, Instant signedAt) {
    DirectorySignature s = new DirectorySignature();
    s.setKeyVersion(version);
    s.setValue(Base64.getEncoder().encodeToString(value));
    s.setSignedAt(utc(signedAt));
    return s;
  }

  private static OffsetDateTime utc(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }
}
