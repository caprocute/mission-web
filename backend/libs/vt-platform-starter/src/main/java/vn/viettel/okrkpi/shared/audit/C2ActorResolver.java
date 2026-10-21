package vn.viettel.okrkpi.shared.audit;

/** Xác định người đang giải mã C2 trong luồng hiện tại (CORE-00-05). */
@FunctionalInterface
public interface C2ActorResolver {

  /** Không bao giờ trả {@code null}; ngoài ngữ cảnh người dùng thì trả {@link C2Actor#SYSTEM}. */
  C2Actor current();
}
