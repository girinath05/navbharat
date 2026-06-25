package com.cts.outward.service;

import com.cts.outward.dto.CxfBatchDTO;
import com.cts.outward.dto.CxfChequeDTO;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds the CIBF XML file matching the exact NPCI sample format:
 *
 * <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
 * <CIBF xmlns="urn:npci:cts:cibf:v1.0" version="1.0">
 *     <BatchInfo>
 *         <BatchId>B-2026-0608-001</BatchId>
 *         <ImageCount>6</ImageCount>
 *         <TotalImageSize>761365</TotalImageSize>
 *         <GeneratedAt>2026-06-08T14:37:28</GeneratedAt>
 *     </BatchInfo>
 *     <ImageList>
 *         <Image>
 *             <SeqNo>1</SeqNo>
 *             <ChequeNo>789012</ChequeNo>
 *             <Side>FRONT</Side>
 *             <FilePath>cheque001_front.png</FilePath>
 *             <FileSize>151149</FileSize>
 *             <Checksum>sha256hex</Checksum>
 *         </Image>
 *         ...
 *     </ImageList>
 * </CIBF>
 *
 * Rules:
 *   - Images are stored as bytea in cts_cheques (front_image, rear_image).
 *   - Each cheque emits up to 2 Image entries: FRONT then BACK.
 *   - If a side has no bytes in DB that Image entry is skipped entirely —
 *     NPCI must not receive an entry with FileSize=0 / empty Checksum.
 *   - ImageCount     = total Image entries emitted.
 *   - TotalImageSize = sum of all image byte lengths.
 *   - FilePath       = logical filename used as NPCI reconciliation key
 *                      (cheque001_front.png, cheque001_back.png, ...).
 *   - Checksum       = SHA-256 hex of the raw image bytes.
 *
  * The .cibf extension is used for CIBF XML files.
  * Content is valid XML.
  */
public class CibfXmlBuilder {

    private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Generates a complete CIBF XML payload as a String for a given batch and list of cheques.
     * Sourced and formatted according to the NPCI CTS image file specification.
     *
     * @param batch   batch header data (used to obtain batchId and other details)
     * @param cheques ordered list of cheques containing raw image byte arrays
     * @param now     generation date-time timestamp written to GeneratedAt
     * @return constructed CIBF XML payload string
     */
    public String generateCibfXmlString(CxfBatchDTO batch,
                         List<CxfChequeDTO> cheques,
                         LocalDateTime now,
                         int offset) {

        // ── Pass 1: compute BatchInfo totals ─────────────────────────────────
        int  imageCount     = 0;
        long totalImageSize = 0L;

        for (CxfChequeDTO cheque : cheques) {
            if (hasValidImageBytes(cheque.getFrontImage())) {
                imageCount++;
                totalImageSize += cheque.getFrontImage().length;
            }
            if (hasValidImageBytes(cheque.getRearImage())) {
                imageCount++;
                totalImageSize += cheque.getRearImage().length;
            }
        }

        // ── Build XML ─────────────────────────────────────────────────────────
        StringBuilder xmlBuilder = new StringBuilder(8192);
        xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        xmlBuilder.append("<CIBF xmlns=\"urn:npci:cts:cibf:v1.0\" version=\"1.0\">\n");

        // BatchInfo
        xmlBuilder.append("    <BatchInfo>\n");
        xmlBuilder.append("        <BatchId>").append(escapeXmlSpecialCharacters(batch.getBatchId())).append("</BatchId>\n");
        xmlBuilder.append("        <ImageCount>").append(imageCount).append("</ImageCount>\n");
        xmlBuilder.append("        <TotalImageSize>").append(totalImageSize).append("</TotalImageSize>\n");
        xmlBuilder.append("        <GeneratedAt>").append(now.format(ISO_DATE_TIME_FORMATTER)).append("</GeneratedAt>\n");
        xmlBuilder.append("    </BatchInfo>\n");

        // ImageList
        xmlBuilder.append("    <ImageList>\n");

        int sequentialNumber = 1;  // global sequential image number across all cheques in this part
        int chequeIndex = offset + 1;  // cheque index → drives filename (cheque001, cheque002, ...)

        for (CxfChequeDTO cheque : cheques) {
            String chequeNo = substituteNullWithEmptyString(cheque.getChequeNo());
            String indexString   = String.format("%03d", chequeIndex);

            // FRONT
            if (hasValidImageBytes(cheque.getFrontImage())) {
                String filePath = "cheque" + indexString + "_front.png";
                xmlBuilder.append("        <Image>\n");
                xmlBuilder.append("            <SeqNo>").append(sequentialNumber++).append("</SeqNo>\n");
                xmlBuilder.append("            <ChequeNo>").append(escapeXmlSpecialCharacters(chequeNo)).append("</ChequeNo>\n");
                xmlBuilder.append("            <Side>FRONT</Side>\n");
                xmlBuilder.append("            <FilePath>").append(filePath).append("</FilePath>\n");
                xmlBuilder.append("            <FileSize>").append(cheque.getFrontImage().length).append("</FileSize>\n");
                xmlBuilder.append("            <Checksum>").append(calculateSha256ChecksumInHex(cheque.getFrontImage())).append("</Checksum>\n");
                xmlBuilder.append("        </Image>\n");
            }

            // BACK
            if (hasValidImageBytes(cheque.getRearImage())) {
                String filePath = "cheque" + indexString + "_back.png";
                xmlBuilder.append("        <Image>\n");
                xmlBuilder.append("            <SeqNo>").append(sequentialNumber++).append("</SeqNo>\n");
                xmlBuilder.append("            <ChequeNo>").append(escapeXmlSpecialCharacters(chequeNo)).append("</ChequeNo>\n");
                xmlBuilder.append("            <Side>BACK</Side>\n");
                xmlBuilder.append("            <FilePath>").append(filePath).append("</FilePath>\n");
                xmlBuilder.append("            <FileSize>").append(cheque.getRearImage().length).append("</FileSize>\n");
                xmlBuilder.append("            <Checksum>").append(calculateSha256ChecksumInHex(cheque.getRearImage())).append("</Checksum>\n");
                xmlBuilder.append("        </Image>\n");
            }

            chequeIndex++;
        }

        xmlBuilder.append("    </ImageList>\n");
        xmlBuilder.append("</CIBF>\n");

        return xmlBuilder.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Verification helper to check if a byte array is non-null and holds actual image data.
     *
     * @param imageBytes byte array to verify
     * @return true if the byte array is non-null and has content, false otherwise
     */
    private static boolean hasValidImageBytes(byte[] imageBytes) {
        return imageBytes != null && imageBytes.length > 0;
    }

    /**
     * Computes the SHA-256 checksum/digest of a byte array and formats it as a hex string.
     * Returns 64 zero characters as a fallback digest on error.
     *
     * @param data byte array to hash
     * @return 64-character SHA-256 checksum hex string
     */
    private static String calculateSha256ChecksumInHex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte hashByte : hashBytes) {
                hex.append(String.format("%02x", hashByte));
            }
            return hex.toString();
        } catch (Exception exception) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    /**
     * Subtitutes a null string reference with a clean empty string.
     *
     * @param inputString input string reference
     * @return empty string if input is null, otherwise the original string reference
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
        return inputString.replace("&",  "&amp;")
                .replace("<",  "&lt;")
                .replace(">",  "&gt;")
                .replace("\"", "&quot;")
                .replace("'",  "&apos;");
    }
}