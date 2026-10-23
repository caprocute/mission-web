package vn.viettel.okrkpi.esign.application.seal;

/** Dịch vụ cấp dấu thời gian RFC 3161 (cổng ra; hiện thực là {@code TsaClient}). */
public interface TimestampAuthority {

  /** Token dấu thời gian (ContentInfo DER) với message imprint SHA-256 là {@code sha256}. */
  byte[] timestampDigest(byte[] sha256);
}
