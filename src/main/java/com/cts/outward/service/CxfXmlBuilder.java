package com.cts.outward.service;

import com.cts.outward.dto.CxfBatchDTO;
import com.cts.outward.dto.CxfChequeDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds CXF XML matching the exact sample format from the MD file:
 *
 * <FileHeader ...>
 *   <Item AccountNo="..." Amount="..." ClearingType="14" CycleNo="01"
 *         DocType="C" ItemSeqNo="..." NumOfImageViews="03"
 *         PayorBankRoutNo="..." PresentingBankRoutNo="560765000"
 *         PresentmentDate="ddmmyyyy" SerialNo="..." TransCode="29"
 *         UserField="BATCHID">
 *     <AddendA BOFDBusDate="ddmmyyyy" BOFDRoutNo="560765000" IFSC="..."/>
 *   </Item>
 *   <FileSummary TotalAmount="..." TotalItemCount="..."/>
 * </FileHeader>
 *
 * File name: CXF_<batchId>_<ddmmyyyy>.XML
 */
public class CxfXmlBuilder {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("ddMMyyyy");

    /** Clearing type per NPCI CTS spec */
    private static final String CLEARING_TYPE = "14";
    private static final String CYCLE_NO      = "01";
    private static final String DOC_TYPE      = "C";
    private static final String TRANS_CODE    = "29";
    private static final String NUM_VIEWS     = "03";

    /**
     * Generates a complete CXF XML payload as a String for a given batch and list of cheques.
     * Sourced and formatted according to the NPCI CTS file specification.
     *
     * @param batch            batch header data (used to obtain batchId and other details)
     * @param cheques          ordered list of cheques to generate items for
     * @param presentingRoutNo routing number of the presenting bank
     * @param ifscCode         IFSC code of the presenting bank branch
     * @param now              generation date-time timestamp written to PresentationDate and BOFDBusDate
     * @return constructed CXF XML payload string
     */
    public String generateCxfXmlString(CxfBatchDTO batch,
                        List<CxfChequeDTO> cheques,
                        String presentingRoutNo,
                        String ifscCode,
                        LocalDateTime now) {

        String dateString   = now.format(DATE_FORMATTER);
        String batchId      = batch.getBatchId();
        long   totalAmount  = calculateTotalAmountInPaise(cheques);

        StringBuilder xmlBuilder = new StringBuilder(8192);
        xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xmlBuilder.append("<FileHeader PresentationDate=\"").append(dateString).append("\"")
          .append(" PresentingBankRoutNo=\"").append(escapeXmlSpecialCharacters(presentingRoutNo)).append("\"")
          .append(" TotalItems=\"").append(cheques.size()).append("\">\n");

        int serial = 1;
        for (CxfChequeDTO cheque : cheques) {
            String seqNo     = String.format("%018d", serial);
            String serialStr = String.format("%06d", serial);
            // Amount in paise (multiply by 100, no decimals)
            long amountInPaise = convertDecimalAmountToPaiseLong(cheque.getAmount());

            xmlBuilder.append("  <Item")
              .append(" AccountNo=\"").append(escapeXmlSpecialCharacters(cheque.getAccountNo())).append("\"")
              .append(" Amount=\"").append(amountInPaise).append("\"")
              .append(" ClearingType=\"").append(CLEARING_TYPE).append("\"")
              .append(" CycleNo=\"").append(CYCLE_NO).append("\"")
              .append(" DocType=\"").append(DOC_TYPE).append("\"")
              .append(" ItemSeqNo=\"").append(seqNo).append("\"")
              .append(" NumOfImageViews=\"").append(NUM_VIEWS).append("\"")
              .append(" PayorBankRoutNo=\"").append(escapeXmlSpecialCharacters(substituteNullWithEmptyString(cheque.getSortCode()))).append("\"")
              .append(" PresentingBankRoutNo=\"").append(escapeXmlSpecialCharacters(presentingRoutNo)).append("\"")
              .append(" PresentmentDate=\"").append(dateString).append("\"")
              .append(" SerialNo=\"").append(serialStr).append("\"")
              .append(" TransCode=\"").append(TRANS_CODE).append("\"")
              .append(" UserField=\"").append(escapeXmlSpecialCharacters(batchId)).append("\"")
              .append(">\n");

            xmlBuilder.append("    <AddendA")
              .append(" BOFDBusDate=\"").append(dateString).append("\"")
              .append(" BOFDRoutNo=\"").append(escapeXmlSpecialCharacters(presentingRoutNo)).append("\"")
              .append(" IFSC=\"").append(escapeXmlSpecialCharacters(substituteNullWithEmptyString(ifscCode))).append("\"")
              .append("/>\n");

            xmlBuilder.append("  </Item>\n");
            serial++;
        }

        xmlBuilder.append("  <FileSummary")
          .append(" TotalAmount=\"").append(totalAmount).append("\"")
          .append(" TotalItemCount=\"").append(cheques.size()).append("\"")
          .append("/>\n");
        xmlBuilder.append("</FileHeader>\n");

        return xmlBuilder.toString();
    }

    // ── helpers ───────────────────────────────────────────

    /**
     * Sums up the monetary amounts of all cheques in the list, converting each to paise.
     *
     * @param cheques list of cheques to sum
     * @return sum of all cheque amounts in paise
     */
    private static long calculateTotalAmountInPaise(List<CxfChequeDTO> cheques) {
        return cheques.stream()
            .map(cheque -> convertDecimalAmountToPaiseLong(cheque.getAmount()))
            .mapToLong(Long::longValue)
            .sum();
    }

    /**
     * Converts a BigDecimal amount to its equivalent value in paise as a long primitive.
     *
     * @param amt amount decimal representation
     * @return amount in paise
     */
    private static long convertDecimalAmountToPaiseLong(BigDecimal amount) {
        if (amount == null) return 0L;
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }

    /**
     * Replaces null strings with empty strings to prevent writing "null" text to output files.
     *
     * @param inputString input string
     * @return safe string
     */
    private static String substituteNullWithEmptyString(String inputString) { return inputString != null ? inputString : ""; }

    /**
     * Escapes standard XML reserved characters to keep the resulting XML documents valid and parser-friendly.
     *
     * @param inputString input raw string
     * @return XML-escaped safe string
     */
    private static String escapeXmlSpecialCharacters(String inputString) {
        if (inputString == null) return "";
        return inputString.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}