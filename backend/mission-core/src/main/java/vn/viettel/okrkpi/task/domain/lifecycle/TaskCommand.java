package vn.viettel.okrkpi.task.domain.lifecycle;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Lệnh chuyển trạng thái nhiệm vụ (TL-01 {@code TaskCommand}); {@link #code()} là khoá của bảng.
 */
public sealed interface TaskCommand
    permits TaskCommand.Assign,
        TaskCommand.Accept,
        TaskCommand.Submit,
        TaskCommand.ApproveAcceptance,
        TaskCommand.RequestRework,
        TaskCommand.Suspend,
        TaskCommand.Resume,
        TaskCommand.Cancel {

  String code();

  /** Lý do kèm lệnh, chuỗi rỗng khi lệnh không mang lý do. */
  default String reason() {
    return "";
  }

  record Assign() implements TaskCommand {
    public String code() {
      return "ASSIGN";
    }
  }

  record Accept() implements TaskCommand {
    public String code() {
      return "ACCEPT";
    }
  }

  record Submit(String deliverableId) implements TaskCommand {
    public String code() {
      return "SUBMIT";
    }
  }

  record ApproveAcceptance(BigDecimal qualityLevel) implements TaskCommand {
    public String code() {
      return "APPROVE";
    }
  }

  record RequestRework(String reason) implements TaskCommand {
    public String code() {
      return "REWORK";
    }

    @Override
    public String reason() {
      return reason == null ? "" : reason;
    }
  }

  record Suspend(String reason) implements TaskCommand {
    public String code() {
      return "SUSPEND";
    }

    @Override
    public String reason() {
      return reason == null ? "" : reason;
    }
  }

  record Resume() implements TaskCommand {
    public String code() {
      return "RESUME";
    }
  }

  record Cancel(String reason) implements TaskCommand {
    public String code() {
      return "CANCEL";
    }

    @Override
    public String reason() {
      return reason == null ? "" : reason;
    }
  }

  /**
   * Dựng lệnh từ mã của bảng chuyển; {@code IllegalArgumentException} khi mã không thuộc tập đóng.
   */
  static TaskCommand of(String code, String reason) {
    return switch (code == null ? "" : code.toUpperCase(Locale.ROOT)) {
      case "ASSIGN" -> new Assign();
      case "ACCEPT" -> new Accept();
      case "SUBMIT" -> new Submit(null);
      case "APPROVE" -> new ApproveAcceptance(null);
      case "REWORK" -> new RequestRework(reason);
      case "SUSPEND" -> new Suspend(reason);
      case "RESUME" -> new Resume();
      case "CANCEL" -> new Cancel(reason);
      default -> throw new IllegalArgumentException("Lệnh chuyển trạng thái không hợp lệ: " + code);
    };
  }
}
