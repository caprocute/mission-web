package vn.viettel.okrkpi.esign.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTO yêu cầu tạo mới mục mẫu ở bộ điều hợp web ({@code api}). */
public record CreateTemplateItemRequest(@NotBlank @Size(max = 200) String name) {}
