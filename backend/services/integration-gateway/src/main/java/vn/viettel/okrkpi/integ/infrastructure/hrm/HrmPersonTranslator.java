package vn.viettel.okrkpi.integ.infrastructure.hrm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.application.port.PersonnelSink;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.infrastructure.hrm.BatchFiles.FormatException;
import vn.viettel.okrkpi.integ.infrastructure.hrm.BatchFiles.Table;
import vn.viettel.okrkpi.integ.infrastructure.hrm.HrmProperties.Profile;
import vn.viettel.okrkpi.integ.infrastructure.hrm.PersonRows.Input;
import vn.viettel.okrkpi.integ.infrastructure.hrm.PersonRows.Result;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Translator nhân sự HRM (DV-13-08): đọc trang REST hoặc tệp lô, kiểm cấu trúc và từng dòng, quy
 * đổi đơn vị ({@code DON_VI}), chức danh ({@code CHUC_DANH}), người đã ánh xạ ({@code NHAN_SU}) qua
 * {@code mapping_xref}, rồi chuyển cả lô cho org-service dưới một lần chạy suy ra từ mã thông điệp
 * (phát lại thư chết dùng lại đúng lần chạy). Tệp sai cấu trúc, có dòng lỗi hay mã chưa ánh xạ thì
 * cả tệp bị từ chối vào thư chết kèm số dòng, không chuyển nửa tệp. Không phát sự kiện outbox:
 * org-service phát EVT-15, EVT-16 sau khi khớp người.
 */
class HrmPersonTranslator implements Translator {

  static final String BAD_STRUCTURE = "SAI_CAU_TRUC";
  static final String BAD_ROWS = "DONG_LOI";
  static final String UNMAPPED = "CHUA_ANH_XA";
  static final String ORG_REJECTED = "ORG_TU_CHOI";
  static final int DETAIL_MAX = 900;

  private final String code;
  private final PersonRows rows;
  private final PersonnelSink sink;
  private final ObjectMapper json;
  private final int batchSize;
  private final boolean partyRoll;

  /** Lần chạy đang chạy của kết nối (DV-13-06), rỗng khi thông điệp đến ngoài lần chạy. */
  @FunctionalInterface
  interface RunningRun {
    Optional<UUID> of(TenantId tenant, UUID connectorId);
  }

  private final RunningRun running;

  HrmPersonTranslator(String code, Profile profile, PersonnelSink sink, ObjectMapper json) {
    this(code, profile, sink, json, (t, c) -> Optional.empty());
  }

  HrmPersonTranslator(
      String code, Profile profile, PersonnelSink sink, ObjectMapper json, RunningRun running) {
    this.running = Objects.requireNonNull(running, "running");
    this.code = Objects.requireNonNull(code, "code");
    this.rows = new PersonRows(profile);
    this.sink = Objects.requireNonNull(sink, "sink");
    this.json = Objects.requireNonNull(json, "json");
    this.batchSize = profile.batchSize();
    this.partyRoll = profile.party() != null;
  }

  @Override
  public List<CanonicalEvent> translate(TranslationContext ctx, InboundMessage message) {
    List<Input> inputs = inputs(message);
    Result result = rows.map(inputs);
    if (!result.errors().isEmpty()) {
      throw new TranslationRejectedException(
          BAD_ROWS,
          "Có "
              + result.errors().size()
              + " dòng lỗi: "
              + PersonRows.describe(result.errors(), DETAIL_MAX));
    }
    Map<Integer, List<String>> unmapped = new LinkedHashMap<>();
    List<PersonnelSink.Person> persons = new ArrayList<>();
    for (PersonRows.Row r : result.rows()) {
      List<String> missing = new ArrayList<>();
      // Người đã ra khỏi Đảng không còn sinh hoạt ở tổ chức đảng nào (DV-13-07): không gửi phân
      // công để org-service ghi lệch đảng tịch, không đòi ánh xạ tổ chức đảng cũ.
      boolean posted = !partyRoll || r.partyMember();
      UUID unit = posted ? resolve(ctx, EntityType.DON_VI, r.orgUnit(), "đơn vị", missing) : null;
      UUID title =
          posted ? resolve(ctx, EntityType.CHUC_DANH, r.jobTitle(), "chức danh", missing) : null;
      if (!missing.isEmpty()) {
        unmapped.put(r.line(), missing);
        continue;
      }
      List<PersonnelSink.Post> posts =
          unit == null || title == null
              ? List.of()
              : List.of(new PersonnelSink.Post(unit, title, r.kind(), r.head(), r.effectiveFrom()));
      UUID mapped =
          ctx.mappings()
              .resolve(
                  ctx.tenant(),
                  ctx.connector().id(),
                  EntityType.NHAN_SU,
                  r.externalId(),
                  ctx.today())
              .orElse(null);
      persons.add(
          new PersonnelSink.Person(
              r.externalId(),
              mapped,
              r.fullName(),
              r.nationalId(),
              r.birthDate(),
              r.employmentStatus(),
              r.partyMember(),
              posts,
              r.effectiveFrom()));
    }
    if (!unmapped.isEmpty()) {
      throw new TranslationRejectedException(
          UNMAPPED,
          "Có "
              + unmapped.size()
              + " dòng chưa ánh xạ mã: "
              + PersonRows.describe(unmapped, DETAIL_MAX));
    }
    if (!persons.isEmpty()) {
      // Trong lần chạy kéo hết trang thì lô thuộc lần chạy đó và worker đóng lần chạy khi xong;
      // ngoài lần chạy thì cả thông điệp là một lần chạy, chia lô để thân yêu cầu không quá lớn.
      Optional<UUID> inRun = running.of(ctx.tenant(), ctx.connector().id());
      UUID run = inRun.orElseGet(() -> runId(ctx, message));
      int rejected = 0;
      for (int from = 0; from < persons.size(); from += batchSize) {
        int to = Math.min(persons.size(), from + batchSize);
        rejected += sink.forward(
            ctx.tenant(),
            code,
            run,
            persons.subList(from, to),
            inRun.isEmpty() && to == persons.size());
      }
      // org-service nhận lô nhưng không ghi được một phần bản ghi: thông điệp không được coi là đã
      // xử lý, mà vào thư chết để phát lại sau khi khắc phục (bản ghi đã ghi khớp lại theo mã ánh xạ)
      if (rejected > 0) {
        throw new TranslationRejectedException(
            ORG_REJECTED,
            "Dịch vụ tổ chức không ghi được "
                + rejected
                + "/"
                + persons.size()
                + " bản ghi của thông điệp");
      }
    }
    return List.of();
  }

  /** Mã lần chạy ổn định theo tenant, kết nối và thông điệp. */
  static UUID runId(TranslationContext ctx, InboundMessage message) {
    return UUID.nameUUIDFromBytes(
        (ctx.tenant().value() + "|" + ctx.connector().id() + "|" + message.externalMsgId())
            .getBytes(StandardCharsets.UTF_8));
  }

  private static UUID resolve(
      TranslationContext ctx,
      EntityType type,
      String external,
      String label,
      List<String> missing) {
    if (external == null) {
      return null;
    }
    Optional<UUID> id =
        ctx.mappings().resolve(ctx.tenant(), ctx.connector().id(), type, external, ctx.today());
    if (id.isEmpty()) {
      missing.add(label + " " + external);
      return null;
    }
    return id.get();
  }

  private List<Input> inputs(InboundMessage message) {
    byte[] raw = message.raw();
    try {
      return switch (message.messageType()) {
        case BatchFiles.TYPE_JSON -> fromJson(raw);
        case BatchFiles.TYPE_CSV -> fromTable(BatchFiles.csv(raw));
        case BatchFiles.TYPE_XLSX -> fromTable(BatchFiles.xlsx(raw));
        case BatchFiles.TYPE_OVERSIZE ->
            throw new FormatException("Tệp vượt dung lượng cho phép của kết nối");
        default -> throw new FormatException("Loại tệp không hỗ trợ");
      };
    } catch (FormatException e) {
      throw new TranslationRejectedException(BAD_STRUCTURE, e.getMessage());
    }
  }

  private List<Input> fromTable(Table table) throws FormatException {
    List<String> missing = rows.missingColumns(table.header());
    if (!missing.isEmpty()) {
      throw new FormatException("Thiếu cột: " + String.join(", ", missing));
    }
    return table.rows().stream().map(rows::fromTable).toList();
  }

  private List<Input> fromJson(byte[] raw) throws FormatException {
    JsonNode items;
    try {
      items = json.readTree(raw);
    } catch (IOException e) {
      throw new FormatException("Trang nhân sự không phải JSON");
    }
    if (items == null || !items.isArray()) {
      throw new FormatException("Trang nhân sự không phải danh sách");
    }
    List<Input> out = new ArrayList<>();
    int i = 0;
    for (JsonNode item : items) {
      out.add(rows.fromJson(++i, item));
    }
    return out;
  }
}
