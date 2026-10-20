package vn.viettel.okrkpi.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.Result;
import vn.viettel.okrkpi.template.application.CreateTemplateItemCommand;
import vn.viettel.okrkpi.template.application.TemplateItemService;
import vn.viettel.okrkpi.template.domain.TemplateItem;
import vn.viettel.okrkpi.template.domain.TemplateItemError;
import vn.viettel.okrkpi.template.domain.TemplateItemId;
import vn.viettel.okrkpi.template.domain.TemplateItemStatus;
import vn.viettel.okrkpi.template.infrastructure.InMemoryTemplateItemRepository;
import vn.viettel.okrkpi.template.internal.TemplateInternalSupport;

/** Kiểm thử đơn vị cho lớp {@code domain}, {@code application} và {@code internal}. */
class TemplateItemServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-10-20T08:00:00Z");

  private InMemoryTemplateItemRepository repository;
  private TemplateItemService service;

  @BeforeEach
  void setUp() {
    repository = new InMemoryTemplateItemRepository();
    service = new TemplateItemService(repository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
  }

  @Test
  void createActivateAndArchiveLifecycle() {
    Result<TemplateItem, TemplateItemError> createdResult =
        service.create(new CreateTemplateItemCommand("  Mục tiêu   mẫu 01  "));
    assertThat(createdResult.isOk()).isTrue();

    TemplateItem created = createdResult.valueIfOk().orElseThrow();
    assertThat(Ids.isUuidV7(created.id().value())).isTrue();
    assertThat(created.id().toString()).isEqualTo(created.id().value().toString());
    assertThat(created.name()).isEqualTo("Mục tiêu mẫu 01");
    assertThat(created.status()).isEqualTo(TemplateItemStatus.NHAP);
    assertThat(created.createdAt()).isEqualTo(FIXED_NOW);
    assertThat(created.version()).isZero();

    // Không thể lưu trữ trực tiếp khi đang ở trạng thái NHAP.
    Result<TemplateItem, TemplateItemError> invalidArchive = created.archive();
    assertThat(invalidArchive.isErr()).isTrue();
    assertThat(invalidArchive.errorIfErr()).contains(TemplateItemError.TRANG_THAI_KHONG_HOP_LE);

    // Kích hoạt qua service.
    Result<TemplateItem, TemplateItemError> activatedResult = service.activate(created.id());
    assertThat(activatedResult.isOk()).isTrue();
    TemplateItem activated = activatedResult.valueIfOk().orElseThrow();
    assertThat(activated.status()).isEqualTo(TemplateItemStatus.KICH_HOAT);
    assertThat(activated.version()).isEqualTo(1L);

    // Không thể kích hoạt lại khi đã ở trạng thái KICH_HOAT.
    Result<TemplateItem, TemplateItemError> duplicateActivate = service.activate(created.id());
    assertThat(duplicateActivate.isErr()).isTrue();
    assertThat(duplicateActivate.errorIfErr()).contains(TemplateItemError.TRANG_THAI_KHONG_HOP_LE);

    // Chuyển sang LUU_TRU từ KICH_HOAT.
    Result<TemplateItem, TemplateItemError> archivedResult = activated.archive();
    assertThat(archivedResult.isOk()).isTrue();
    assertThat(archivedResult.valueIfOk().orElseThrow().status())
        .isEqualTo(TemplateItemStatus.LUU_TRU);
    assertThat(archivedResult.valueIfOk().orElseThrow().version()).isEqualTo(2L);

    // Tìm kiếm theo ID và liệt kê.
    assertThat(service.findById(created.id()).valueIfOk()).contains(activated);
    assertThat(service.listAll()).containsExactly(activated);
  }

  @Test
  void validationAndNotFoundErrors() {
    Result<TemplateItem, TemplateItemError> blankResult =
        service.create(new CreateTemplateItemCommand("   "));
    assertThat(blankResult.errorIfErr()).contains(TemplateItemError.TEN_TRONG);

    Result<TemplateItem, TemplateItemError> nullNameResult =
        service.create(new CreateTemplateItemCommand(null));
    assertThat(nullNameResult.errorIfErr()).contains(TemplateItemError.TEN_TRONG);

    String overLimit = "a".repeat(TemplateItem.MAX_NAME_LENGTH + 1);
    Result<TemplateItem, TemplateItemError> tooLongResult =
        service.create(new CreateTemplateItemCommand(overLimit));
    assertThat(tooLongResult.errorIfErr()).contains(TemplateItemError.TEN_QUA_DAI);
    assertThat(TemplateItemError.TEN_QUA_DAI.catalogCode()).isEqualTo("COMMON-422-VALIDATION");
    assertThat(TemplateItemError.TEN_QUA_DAI.detail()).isNotBlank();

    TemplateItemId unknownId = TemplateItemId.of(UUID.randomUUID());
    assertThat(service.findById(unknownId).errorIfErr()).contains(TemplateItemError.KHONG_TIM_THAY);
    assertThat(service.activate(unknownId).errorIfErr()).contains(TemplateItemError.KHONG_TIM_THAY);

    assertThat(TemplateInternalSupport.normalizeWhitespace(null)).isNull();
    assertThat(new TemplateItemService(repository).listAll()).isEmpty();
    assertThatThrownBy(() -> TemplateItemId.of(null)).isInstanceOf(NullPointerException.class);
  }
}
