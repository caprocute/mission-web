package vn.viettel.okrkpi.template.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.kernel.Result;
import vn.viettel.okrkpi.template.domain.TemplateItem;
import vn.viettel.okrkpi.template.domain.TemplateItemError;
import vn.viettel.okrkpi.template.domain.TemplateItemId;
import vn.viettel.okrkpi.template.domain.TemplateItemRepository;
import vn.viettel.okrkpi.template.internal.TemplateInternalSupport;

/**
 * Thực thi use case mẫu (TL-01: {@code @Transactional} chỉ đặt ở gói {@code application}; lớp
 * {@code application} không nhìn thấy gói {@code infrastructure} hay {@code api}).
 */
@Service
@Transactional
public class TemplateItemService implements TemplateItemUseCase {

  private final TemplateItemRepository repository;
  private final Clock clock;

  @Autowired
  public TemplateItemService(TemplateItemRepository repository) {
    this(repository, Clock.systemUTC());
  }

  public TemplateItemService(TemplateItemRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Result<TemplateItem, TemplateItemError> create(CreateTemplateItemCommand command) {
    Objects.requireNonNull(command, "command");
    String sanitized = TemplateInternalSupport.normalizeWhitespace(command.name());
    return TemplateItem.create(sanitized, Instant.now(clock)).map(repository::save);
  }

  @Override
  public Result<TemplateItem, TemplateItemError> activate(TemplateItemId id) {
    Objects.requireNonNull(id, "id");
    return repository
        .findById(id)
        .<Result<TemplateItem, TemplateItemError>>map(item -> item.activate().map(repository::save))
        .orElseGet(() -> Result.err(TemplateItemError.KHONG_TIM_THAY));
  }

  @Override
  @Transactional(readOnly = true)
  public Result<TemplateItem, TemplateItemError> findById(TemplateItemId id) {
    Objects.requireNonNull(id, "id");
    return repository
        .findById(id)
        .<Result<TemplateItem, TemplateItemError>>map(Result::ok)
        .orElseGet(() -> Result.err(TemplateItemError.KHONG_TIM_THAY));
  }

  @Override
  @Transactional(readOnly = true)
  public List<TemplateItem> listAll() {
    return repository.findAll();
  }
}
