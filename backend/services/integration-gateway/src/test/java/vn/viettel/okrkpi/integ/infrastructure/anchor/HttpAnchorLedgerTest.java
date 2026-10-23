package vn.viettel.okrkpi.integ.infrastructure.anchor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.anchor.CellAnchor;

class HttpAnchorLedgerTest {

  static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void parsesLedgerAnchorDocumentOfFirstSeal() throws Exception {
    CellAnchor a =
        HttpAnchorLedger.parse(
            JSON.readTree(
                """
                {"day":"2026-10-22","cellCode":"cell-hcm",
                 "cellSealId":"0192f1a2-0000-7000-8000-0000001309e1",
                 "windowStart":"2026-10-22T16:00:00Z","windowEnd":"2026-10-22T17:00:00Z",
                 "cellRoot":"%s","prevCellRoot":"","signerKeyId":"k1",
                 "signature":"AQI=","signerCertSha256":"ab","tsaToken":"Aw=="}
                """
                    .formatted("ff".repeat(32))));
    assertThat(a.prevCellRoot()).isNull();
    assertThat(a.tsaGenTime()).isNull();
    assertThat(a.cellRoot()).hasSize(32);
    assertThat(a.signature()).containsExactly(1, 2);
  }

  @Test
  void rejectsMalformedDocument() {
    assertThatThrownBy(() -> HttpAnchorLedger.parse(JSON.readTree("{\"cellSealId\":\"x\"}")))
        .isInstanceOf(IOException.class);
  }
}
