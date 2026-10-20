package vn.viettel.okrkpi.shared.kernel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Kết quả của một phép nghiệp vụ trong lớp domain: thành công kèm giá trị, hoặc thất bại kèm lỗi
 * nghiệp vụ (thường là một enum hoặc record mang mã lỗi). Lớp application chuyển {@link Err} thành
 * ngoại lệ có mã của danh mục lỗi; domain không ném ngoại lệ cho lỗi nghiệp vụ dự kiến được.
 *
 * @param <T> kiểu giá trị khi thành công
 * @param <E> kiểu lỗi khi thất bại
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

  /** Thành công; {@code value} có thể {@code null} cho phép không trả giá trị. */
  static <T, E> Result<T, E> ok(T value) {
    return new Ok<>(value);
  }

  /** Thất bại với lỗi không rỗng. */
  static <T, E> Result<T, E> err(E error) {
    return new Err<>(error);
  }

  boolean isOk();

  default boolean isErr() {
    return !isOk();
  }

  /** Giá trị khi thành công (rỗng khi thất bại hoặc giá trị {@code null}). */
  Optional<T> valueIfOk();

  /** Lỗi khi thất bại. */
  Optional<E> errorIfErr();

  <U> Result<U, E> map(Function<? super T, ? extends U> mapper);

  <F> Result<T, F> mapErr(Function<? super E, ? extends F> mapper);

  <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> mapper);

  <R> R fold(Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr);

  /** Giá trị khi thành công, hoặc {@code other} khi thất bại. */
  default T orElse(T other) {
    return fold(v -> v, e -> other);
  }

  /** Giá trị khi thành công, hoặc ném ngoại lệ dựng từ lỗi. */
  <X extends RuntimeException> T orElseThrow(Function<? super E, ? extends X> toException);

  /**
   * Nhánh thành công.
   *
   * @param value giá trị, có thể {@code null}
   * @param <T> kiểu giá trị
   * @param <E> kiểu lỗi
   */
  record Ok<T, E>(T value) implements Result<T, E> {

    @Override
    public boolean isOk() {
      return true;
    }

    @Override
    public Optional<T> valueIfOk() {
      return Optional.ofNullable(value);
    }

    @Override
    public Optional<E> errorIfErr() {
      return Optional.empty();
    }

    @Override
    public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
      return new Ok<>(mapper.apply(value));
    }

    @Override
    public <F> Result<T, F> mapErr(Function<? super E, ? extends F> mapper) {
      return new Ok<>(value);
    }

    @Override
    public <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> mapper) {
      return Objects.requireNonNull(mapper.apply(value), "flatMap trả null");
    }

    @Override
    public <R> R fold(
        Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr) {
      return onOk.apply(value);
    }

    @Override
    public <X extends RuntimeException> T orElseThrow(
        Function<? super E, ? extends X> toException) {
      return value;
    }
  }

  /**
   * Nhánh thất bại.
   *
   * @param error lỗi, không rỗng
   * @param <T> kiểu giá trị
   * @param <E> kiểu lỗi
   */
  record Err<T, E>(E error) implements Result<T, E> {

    public Err {
      Objects.requireNonNull(error, "Result.Err.error");
    }

    @Override
    public boolean isOk() {
      return false;
    }

    @Override
    public Optional<T> valueIfOk() {
      return Optional.empty();
    }

    @Override
    public Optional<E> errorIfErr() {
      return Optional.of(error);
    }

    @Override
    public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
      return new Err<>(error);
    }

    @Override
    public <F> Result<T, F> mapErr(Function<? super E, ? extends F> mapper) {
      return new Err<>(mapper.apply(error));
    }

    @Override
    public <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> mapper) {
      return new Err<>(error);
    }

    @Override
    public <R> R fold(
        Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr) {
      return onErr.apply(error);
    }

    @Override
    public <X extends RuntimeException> T orElseThrow(
        Function<? super E, ? extends X> toException) {
      throw toException.apply(error);
    }
  }
}
