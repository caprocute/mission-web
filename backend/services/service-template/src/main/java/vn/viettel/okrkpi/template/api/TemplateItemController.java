package vn.viettel.okrkpi.template.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.template.application.CreateTemplateItemCommand;
import vn.viettel.okrkpi.template.application.TemplateItemUseCase;
import vn.viettel.okrkpi.template.domain.TemplateItem;
import vn.viettel.okrkpi.template.domain.TemplateItemError;
import vn.viettel.okrkpi.template.domain.TemplateItemId;

/**
 * Bộ điều hợp web mẫu (TL-01: {@code @RestController} chỉ được đặt ở gói {@code api} hoặc {@code
 * infrastructure.web}).
 */
@RestController
@RequestMapping("/api/v1/template-items")
public class TemplateItemController {

  private final TemplateItemUseCase useCase;

  public TemplateItemController(TemplateItemUseCase useCase) {
    this.useCase = useCase;
  }

  @PostMapping
  public ResponseEntity<TemplateItemResponse> create(
      @Valid @RequestBody CreateTemplateItemRequest request) {
    TemplateItem created =
        useCase
            .create(new CreateTemplateItemCommand(request.name()))
            .orElseThrow(TemplateItemController::toBusinessException);
    return ResponseEntity.status(HttpStatus.CREATED).body(TemplateItemResponse.from(created));
  }

  @PostMapping("/{id}/activate")
  public ResponseEntity<TemplateItemResponse> activate(@PathVariable UUID id) {
    TemplateItem activated =
        useCase
            .activate(TemplateItemId.of(id))
            .orElseThrow(TemplateItemController::toBusinessException);
    return ResponseEntity.ok(TemplateItemResponse.from(activated));
  }

  @GetMapping("/{id}")
  public ResponseEntity<TemplateItemResponse> getById(@PathVariable UUID id) {
    TemplateItem found =
        useCase
            .findById(TemplateItemId.of(id))
            .orElseThrow(TemplateItemController::toBusinessException);
    return ResponseEntity.ok(TemplateItemResponse.from(found));
  }

  @GetMapping
  public ResponseEntity<List<TemplateItemResponse>> listAll() {
    List<TemplateItemResponse> items =
        useCase.listAll().stream().map(TemplateItemResponse::from).toList();
    return ResponseEntity.ok(items);
  }

  private static BusinessException toBusinessException(TemplateItemError error) {
    return new BusinessException(error.catalogCode(), error.detail());
  }
}
