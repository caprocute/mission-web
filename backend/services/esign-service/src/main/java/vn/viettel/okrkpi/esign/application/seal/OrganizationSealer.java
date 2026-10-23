package vn.viettel.okrkpi.esign.application.seal;

import java.util.UUID;

/**
 * Cổng ký bằng chứng thư của tổ chức (dấu số của cell hoặc của đơn vị), không qua xác nhận của một
 * người ký: khoá nằm ở HSM của nhà cung cấp (CSC chế độ đồng bộ) hoặc tệp khoá do OpenBao Agent
 * đặt.
 */
public interface OrganizationSealer {

  /** Chữ ký trên băm SHA-256; {@code algorithm} theo tên JCA, {@code publicKey} là SPKI DER. */
  record Seal(byte[] signature, String algorithm, byte[] publicKey) {}

  Seal seal(UUID tenantId, byte[] sha256);
}
