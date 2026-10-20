package vn.viettel.okrkpi.org.domain;

import java.util.UUID;

/** Một phiên bản dòng của bảng hai trục thời gian: {@code version_id} riêng, {@code id} ổn định. */
public interface Versioned<T extends Versioned<T>> {

  UUID versionId();

  UUID id();

  ValidPeriod validPeriod();

  /** Bản sao với khoảng hiệu lực khác; {@code versionId} được cấp lại khi chèn. */
  T withValidPeriod(ValidPeriod period);
}
