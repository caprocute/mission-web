package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.io.ByteArrayInputStream;
import java.util.stream.Collectors;
import org.verapdf.core.ModelParsingException;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.PdfaValidator;

/**
 * Kiểm PDF/A-2b bằng veraPDF (MPL 2.0, mô hình Greenfield): chạy trong tiến trình, không cần dịch
 * vụ riêng. Tệp không đọc được tính là không đạt.
 */
public class VeraPdfValidator implements PdfaValidator {

  private static final int MAX_CLAUSES = 5;

  public VeraPdfValidator() {
    VeraGreenfieldFoundryProvider.initialise();
  }

  @Override
  public Result validate(byte[] pdf) {
    PDFAFlavour flavour = PDFAFlavour.PDFA_2_B;
    try (PDFAParser parser =
            Foundries.defaultInstance().createParser(new ByteArrayInputStream(pdf), flavour);
        PDFAValidator validator = Foundries.defaultInstance().createValidator(flavour, false)) {
      ValidationResult result = validator.validate(parser);
      if (result.isCompliant()) {
        return new Result(true, "");
      }
      String clauses =
          result.getTestAssertions().stream()
              .filter(a -> a.getStatus() == TestAssertion.Status.FAILED)
              .map(a -> a.getRuleId().getClause())
              .distinct()
              .limit(MAX_CLAUSES)
              .collect(Collectors.joining(", "));
      return new Result(false, "vi phạm điều " + clauses);
    } catch (ModelParsingException e) {
      return new Result(false, "không đọc được tệp PDF");
    } catch (Exception e) {
      return new Result(false, "lỗi khi kiểm: " + e.getMessage());
    }
  }
}
