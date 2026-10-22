package vn.viettel.okrkpi.config.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * UI Pack của tenant ({@code config.ui_pack}).
 *
 * @param currentVersionId phiên bản đang phát hành, {@code null} khi chưa phát hành
 */
public record UiPack(UUID id, String code, String name, UUID currentVersionId, int rowVersion) {

  public UiPack {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
  }
}
