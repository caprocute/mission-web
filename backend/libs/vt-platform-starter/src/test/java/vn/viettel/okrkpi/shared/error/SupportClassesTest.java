package vn.viettel.okrkpi.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.ObjectError;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import vn.viettel.okrkpi.okr.infrastructure.web.OkrProbeController;

class SupportClassesTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  // ---- W3cTraceIdResolver ----

  @Test
  void traceIdLayTuTraceparentHopLe() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("traceparent", "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01");

    W3cTraceIdResolver r = new W3cTraceIdResolver();
    assertThat(r.resolve(req)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(r.resolve(req)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
  }

  @Test
  void traceIdSinhMoiKhiHeaderSaiVaGiuNguyenTrongYeuCau() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("traceparent", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01");

    W3cTraceIdResolver r = new W3cTraceIdResolver();
    String id = r.resolve(req);
    assertThat(id).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
    assertThat(r.resolve(req)).isEqualTo(id);
    assertThat(r.resolve(new MockHttpServletRequest())).isNotEqualTo(id);
  }

  @Test
  void traceIdLayTuMdc() {
    org.slf4j.MDC.put("traceId", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    try {
      assertThat(new W3cTraceIdResolver().resolve(new MockHttpServletRequest()))
          .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    } finally {
      org.slf4j.MDC.remove("traceId");
    }
    org.slf4j.MDC.put("trace_id", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    try {
      assertThat(new W3cTraceIdResolver().resolve(new MockHttpServletRequest()))
          .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    } finally {
      org.slf4j.MDC.remove("trace_id");
    }
  }

  @Test
  void traceparentSaiDang() {
    assertThat(W3cTraceIdResolver.fromTraceparent(null)).isNull();
    assertThat(W3cTraceIdResolver.fromTraceparent("khong-hop-le")).isNull();
    assertThat(W3cTraceIdResolver.valid(null)).isNull();
    assertThat(W3cTraceIdResolver.valid("xyz")).isNull();
  }

  // ---- ErrorModuleResolver ----

  @Test
  void moduleTheoGoiControllerRoiCauHinhRoiCommon() throws Exception {
    ErrorCatalog catalog = ErrorCatalog.standard();
    MockHttpServletRequest okr = new MockHttpServletRequest();
    okr.setAttribute(
        HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
        new HandlerMethod(
            new OkrProbeController(), OkrProbeController.class.getDeclaredMethod("optimistic")));

    assertThat(new ErrorModuleResolver(catalog, null).resolve(okr)).isEqualTo("OKR");
    assertThat(new ErrorModuleResolver(catalog, " registry ").resolve(okr)).isEqualTo("OKR");

    MockHttpServletRequest other = new MockHttpServletRequest();
    other.setAttribute(
        HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE,
        new HandlerMethod(
            new SupportClassesTest(),
            SupportClassesTest.class.getDeclaredMethod("traceparentSaiDang")));
    assertThat(new ErrorModuleResolver(catalog, "registry").resolve(other)).isEqualTo("REGISTRY");
    assertThat(new ErrorModuleResolver(catalog, " ").resolve(other)).isEqualTo("COMMON");
    assertThat(new ErrorModuleResolver(catalog, null).resolve(null)).isEqualTo("COMMON");

    ErrorModuleResolver r = new ErrorModuleResolver(catalog, null);
    assertThat(r.fromPackage("vn.viettel.okrkpi.task")).isEqualTo("TASK");
    assertThat(r.fromPackage("vn.viettel.okrkpi.shared.error")).isNull();
    assertThat(r.fromPackage("com.example.task")).isNull();
    assertThat(r.fromPackage(null)).isNull();
  }

  // ---- ValidationErrors ----

  record Mau(
      @Size(min = 2, max = 5) String a,
      @Size(min = 3) String b,
      @Min(1) int c,
      @Positive int d,
      @Negative int e,
      @Pattern(regexp = "[0-9]+") String f,
      @Email String g,
      @Past LocalDate h,
      @Future LocalDate i,
      @NotEmpty List<@Size(max = 1) String> j) {}

  @Test
  void thongDiepTiengVietChoRangBuocChuan() {
    var violations =
        validator.validate(
            new Mau(
                "x",
                "y",
                0,
                0,
                0,
                "abc",
                "khong-email",
                LocalDate.of(2999, 1, 1),
                LocalDate.of(2000, 1, 1),
                List.of("ok", "dai")));
    List<ProblemFieldError> errors = ValidationErrors.fromViolations(violations);

    assertThat(errors)
        .extracting(e -> e.field() + "=" + e.message())
        .containsExactly(
            "a=Độ dài phải từ 2 đến 5",
            "b=Độ dài tối thiểu là 3",
            "c=Giá trị tối thiểu là 1",
            "d=Giá trị phải lớn hơn 0",
            "e=Giá trị phải nhỏ hơn 0",
            "f=Giá trị không đúng định dạng",
            "g=Địa chỉ thư điện tử không hợp lệ",
            "h=Thời điểm không được ở tương lai",
            "i=Thời điểm không được ở quá khứ",
            "j[0]=Độ dài tối đa là 1",
            "j[1]=Độ dài tối đa là 1");
  }

  @Test
  void thongDiepMacDinhVaThongDiepKyThuat() {
    assertThat(ValidationErrors.defaultMessage(null, Map.of())).isEqualTo(ValidationErrors.GENERIC);
    assertThat(ValidationErrors.defaultMessage("AssertTrue", Map.of()))
        .isEqualTo(ValidationErrors.GENERIC);
    assertThat(ValidationErrors.defaultMessage("Range", Map.of("min", 1, "max", 9)))
        .isEqualTo("Giá trị phải từ 1 đến 9");
    assertThat(ValidationErrors.defaultMessage("PositiveOrZero", Map.of()))
        .isEqualTo("Giá trị không được âm");
    assertThat(ValidationErrors.defaultMessage("NegativeOrZero", Map.of()))
        .isEqualTo("Giá trị không được dương");
    assertThat(ValidationErrors.defaultMessage("Digits", Map.of()))
        .isEqualTo("Số chữ số không hợp lệ");
    assertThat(ValidationErrors.sizeMessage(0, Integer.MAX_VALUE))
        .isEqualTo(ValidationErrors.GENERIC);
    assertThat(ValidationErrors.sizeMessage(null, null)).isEqualTo(ValidationErrors.GENERIC);

    assertThat(
            ValidationErrors.message(
                new ObjectError("x", new String[] {"typeMismatch.x"}, null, "Failed to convert")))
        .isEqualTo(ValidationErrors.TYPE_MISMATCH);
    assertThat(
            ValidationErrors.message(
                new ObjectError(
                    "x", new String[] {"khac"}, null, "Failed to convert java.lang.String")))
        .isEqualTo(ValidationErrors.GENERIC);
    assertThat(ValidationErrors.message(new ObjectError("x", new String[] {"khac"}, null, null)))
        .isEqualTo(ValidationErrors.GENERIC);
    assertThat(
            ValidationErrors.message(
                new ObjectError(
                    "x", new String[] {"khac"}, null, "Ngày kết thúc phải sau ngày bắt đầu")))
        .isEqualTo("Ngày kết thúc phải sau ngày bắt đầu");
    assertThat(ValidationErrors.join(null, "a")).isEqualTo("a");
    assertThat(ValidationErrors.join("p", "")).isEqualTo("p");
    assertThat(ValidationErrors.join("p", "a")).isEqualTo("p.a");
  }

  // ---- Kiểu phụ trợ ----

  @Test
  void problemTypesTheoStatus() {
    assertThat(ProblemTypes.forStatus(418).type()).endsWith("/bad-request");
    assertThat(ProblemTypes.forStatus(599).type()).endsWith("/internal-error");
    assertThat(ProblemTypes.forStatus(503).title()).isEqualTo("Hệ thống đang bận");
  }

  @Test
  void problemFieldErrorVaVersionConflict() {
    assertThat(ProblemFieldError.of("a", "b").rule()).isNull();
    assertThatThrownBy(() -> ProblemFieldError.of(null, "b"))
        .isInstanceOf(NullPointerException.class);

    VersionConflictException ex = new VersionConflictException(null, null, 7L);
    assertThat(ex.module()).isNull();
    assertThat(ex.expectedVersion()).isNull();
    assertThat(ex.currentVersion()).isEqualTo(7L);
    assertThat(ex.detail()).isEqualTo(VersionConflictException.DEFAULT_DETAIL);
    assertThat(ProblemFactory.safePath("/a b")).isNull();
    assertThat(ProblemFactory.safePath("/api/v1/tasks/1")).isEqualTo("/api/v1/tasks/1");
  }
}
