package vn.viettel.okrkpi.task.application.document.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Extraction;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Fields;
import vn.viettel.okrkpi.task.application.document.port.DocExtractions.Suggestion;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Content;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.DocRow;

class DocumentPortRecordsTest {

  @Test
  void nullListsBecomeEmpty() {
    Fields f = new Fields("t", null, null, null, null, null, null, null, null, null, null);
    assertThat(f.coUnits()).isEmpty();
    assertThat(f.coUnitNames()).isEmpty();
    assertThat(
            new Suggestion(UUID.randomUUID(), 1, BigDecimal.ONE, "CHO_XAC_NHAN", f, f, null, null)
                .editedFields())
        .isEmpty();
    assertThat(new Extraction("XONG", null).suggestions()).isEmpty();
    assertThat(new Content(null, "EdXML", null, null, null, null, null, "C1").paragraphs())
        .isEmpty();
    assertThat(
            new DocRow(
                    UUID.randomUUID(),
                    null,
                    "EDXML",
                    "X",
                    "e",
                    "1",
                    LocalDate.now(),
                    "i",
                    "x",
                    "EdXML",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "C1",
                    false,
                    1,
                    null,
                    Instant.now(),
                    "CHO_XU_LY",
                    null,
                    0)
                .paragraphs())
        .isEmpty();
  }
}
