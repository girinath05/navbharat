/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : CtsZipParserImpl.java
 *  Package     : com.cts.outward.parser
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Parses CTS ZIP files (Structure A: folder-per-cheque,
 *                Structure B: flat batch XML). Extracts drawee account,
 *                payee account, BaseNo, TC from XML and MICR line.
 * ============================================================
 */
package com.cts.outward.parser;

import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;

public class CtsZipParserImpl implements CtsParser {

    private static final Logger        LOG = Logger.getLogger(CtsZipParserImpl.class.getName());
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Override
    public ParseResult parse(byte[] zipBytes, String zipName) {
        LOG.info("CtsZipParserImpl.parse() — file: " + zipName + " | size: " + zipBytes.length + " bytes");
        try {
            Map<String, byte[]> entries = extractZipEntries(zipBytes);
            LOG.info("ZIP entries found: " + entries.keySet());
            boolean isFolderPerCheque = detectFolderPerCheque(entries);
            LOG.info("ZIP structure: " + (isFolderPerCheque ? "FOLDER-PER-CHEQUE (Structure A)" : "FLAT BATCH XML (Structure B)"));
            if (isFolderPerCheque) {
                return parseFolderPerCheque(entries, zipName);
            } else {
                return parseFlatBatch(entries, zipName);
            }
        } catch (Exception ex) {
            LOG.severe("ZIP parse failed: " + ex.getMessage());
            throw new RuntimeException("Failed to parse ZIP: " + ex.getMessage(), ex);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STRUCTURE DETECTION
    // ══════════════════════════════════════════════════════════════════

    private boolean detectFolderPerCheque(Map<String, byte[]> entries) {
        // Structure A: BATCH0102/CHQ001/cheque.xml  → split("/").length == 3  → true
        // Structure B: BATCH0102/batch.xml          → split("/").length == 2  → false
        //              batch.xml                    → split("/").length == 1  → false
        return entries.keySet().stream()
            .filter(k -> k.toLowerCase().endsWith(".xml"))
            .anyMatch(k -> k.split("/").length >= 3);
    }

    // ══════════════════════════════════════════════════════════════════
    // STRUCTURE A — Folder-per-cheque
    // ══════════════════════════════════════════════════════════════════

    private ParseResult parseFolderPerCheque(Map<String, byte[]> entries, String zipName) throws Exception {
        Map<String, Map<String, byte[]>> folders = groupByFolder(entries);
        LOG.info("Cheque folders detected: " + folders.keySet());

        String      batchId    = buildBatchId(zipName);
        BatchEntity batch      = buildBatchEntity(batchId);
        List<ChequeEntity> cheques = new ArrayList<>();
        BigDecimal  batchTotal = BigDecimal.ZERO;

        for (Map.Entry<String, Map<String, byte[]>> folder : folders.entrySet()) {
            String              folderName  = folder.getKey();
            Map<String, byte[]> folderFiles = folder.getValue();

            byte[] xmlBytes = findXmlInFolder(folderFiles);
            if (xmlBytes == null) {
                LOG.warning("No XML in folder: " + folderName + " — skipping");
                continue;
            }

            ChequeEntity cheque = parsePerChequeXml(new ByteArrayInputStream(xmlBytes), batchId, folderName);
            if (cheque == null) {
                LOG.warning("Failed to parse cheque XML in folder: " + folderName);
                continue;
            }

            cheque.setFrontImage(findImage(folderFiles, true));
            cheque.setRearImage(findImage(folderFiles, false));
            cheques.add(cheque);

            if (cheque.getAmount() != null) batchTotal = batchTotal.add(cheque.getAmount());
            LOG.info("  Parsed cheque: " + cheque.getChequeNo()
                + " | DraweeAcct: " + cheque.getAccountNo()
                + " | PayeeAcct: "  + cheque.getPayeeAccountNo()
                + " | BaseNo: "     + cheque.getBaseNo()
                + " | TC: "         + cheque.getTransactionCode()
                + " | Amount: "     + cheque.getAmount());
        }

        batch.setTotalCheques(cheques.size());
        batch.setTotalAmount(batchTotal);
        batch.setStatus(BatchStatus.DRAFT.db());
        batch.setUpdatedAt(LocalDateTime.now());
        LOG.info("ParseResult — Batch: " + batchId + " | Cheques: " + cheques.size() + " | Total: ₹" + batchTotal);
        return new ParseResult(batch, cheques);
    }

    // ══════════════════════════════════════════════════════════════════
    // STRUCTURE B — Flat batch XML
    // ══════════════════════════════════════════════════════════════════

    private ParseResult parseFlatBatch(Map<String, byte[]> entries, String zipName) throws Exception {
        String      batchId  = buildBatchId(zipName);
        BatchEntity batch    = buildBatchEntity(batchId);
        byte[]      xmlBytes = null;

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().toLowerCase().endsWith(".xml")) {
                xmlBytes = e.getValue();
                LOG.info("Master XML found: " + e.getKey());
                break;
            }
        }
        if (xmlBytes == null) throw new RuntimeException("No XML file found in ZIP");

        List<ChequeEntity> cheques = parseMasterXml(new ByteArrayInputStream(xmlBytes), batchId, batch);

        // Attach images by cheque number
        for (ChequeEntity cheque : cheques) {
            String no = cheque.getChequeNo();
            if (no == null) continue;
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String fname = e.getKey().toLowerCase();
                if (!isImage(fname)) continue;
                String detectedNo = extractChequeNoFromFilename(e.getKey());
                if (!no.equals(detectedNo)) continue;
                if (isFrontImageFile(fname)) cheque.setFrontImage(e.getValue());
                else                         cheque.setRearImage(e.getValue());
            }
        }

        BigDecimal batchTotal = cheques.stream()
            .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        batch.setTotalCheques(cheques.size());
        batch.setTotalAmount(batchTotal);
        batch.setStatus(BatchStatus.DRAFT.db());
        batch.setUpdatedAt(LocalDateTime.now());
        return new ParseResult(batch, cheques);
    }

    // ══════════════════════════════════════════════════════════════════
    // XML PARSERS
    // ══════════════════════════════════════════════════════════════════

    private ChequeEntity parsePerChequeXml(InputStream xmlStream, String batchId, String folderName) {
        try {
            Document doc  = buildDoc(xmlStream);
            Element  root = doc.getDocumentElement();

            ChequeEntity c = new ChequeEntity();
            c.setBatchId(batchId);
            c.setChequeNo(firstTag(root, "ChequeNumber", "ChequeNo", "InstrNo"));

            // ── DRAWEE account: A/c No printed on FRONT cheque face ──────────
            // From cheque image: "A/c No. 20000123456789"
            c.setAccountNo(firstTag(root, "AccountNumber", "AccountNo", "AcctNo"));

            // ── PAYEE credit account: written on REAR endorsement ────────────
            // From cheque image rear: "A/c No. / खाता संख्या 603200987654321"
            // Optional — stays null if absent until operator fills it in popup
            String payeeAcct = firstTag(root, "PayeeAccountNumber", "PayeeAcctNo", "PayeeAccountNo");
            if (payeeAcct != null) c.setPayeeAccountNo(payeeAcct);

            c.setDrawerName(firstTag(root, "DrawerName", "Drawer", "DrawerBank"));
            c.setPayeeName(firstTag(root, "PayeeName", "Payee"));
            c.setChequeDate(firstTag(root, "ChequeDate", "Date"));
            c.setSortCode(firstTag(root, "SortCode", "MICRCode", "MicrCode"));

            String chequeId = firstTag(root, "ChequeId", "ChequeID");
            c.setChequeId(chequeId != null ? chequeId : folderName);

            // ── Parse MICR line: ChequeNo SortCode BaseNo TC ─────────────────
            // e.g. "123456 110532005 20000123456789 29"
            parseMicrLine(c, firstTag(root, "MICRLine"));

            // ── Fallback: explicit TC tag ─────────────────────────────────────
            if (isBlank(c.getTransactionCode())) {
                String tc = firstTag(root, "TC", "TransactionCode", "TxCode");
                if (tc != null) c.setTransactionCode(tc);
            }

            // ── Fallback: explicit BaseNo tag ─────────────────────────────────
            if (isBlank(c.getBaseNo())) {
                String baseNo = firstTag(root, "BaseNo", "BaseNumber");
                if (baseNo != null) c.setBaseNo(baseNo);
            }

            // ── Amount in words ───────────────────────────────────────────────
            String amtWords = firstTag(root, "AmountInWords", "AmtInWords", "AmountWords");
            if (amtWords != null) c.setAmountInWords(amtWords);

            // ── Amount in digits ──────────────────────────────────────────────
            String amtStr = firstTag(root, "Amount", "Amt");
            if (amtStr != null) {
                try { c.setAmount(new BigDecimal(amtStr)); }
                catch (NumberFormatException e) { LOG.warning("Bad amount in " + folderName + ": " + amtStr); }
            }

            String iqa = firstTag(root, "IQA", "IqaStatus");
            c.setIqaStatus(iqa != null ? iqa : "Pass");
            c.setVerStatus(ChequeStatus.PENDING.db());
            c.setStatus(ChequeStatus.PENDING.db());

            if (isBlank(c.getChequeNo())) c.setChequeNo(folderName);
            c.setCreatedAt(LocalDateTime.now());
            c.setUpdatedAt(LocalDateTime.now());
            return c;

        } catch (Exception ex) {
            LOG.severe("parsePerChequeXml error in " + folderName + ": " + ex.getMessage());
            return null;
        }
    }

    private List<ChequeEntity> parseMasterXml(InputStream xmlStream, String batchId, BatchEntity batch) throws Exception {
        List<ChequeEntity> list = new ArrayList<>();
        Document doc  = buildDoc(xmlStream);
        Element  root = doc.getDocumentElement();

        String xmlBatchId = firstTag(root, "BatchId", "BatchID");
        if (xmlBatchId != null && !xmlBatchId.isBlank()) {
            batch.setBatchId(xmlBatchId);
            batchId = xmlBatchId;
        }

        NodeList chequeNodes = root.getElementsByTagName("Cheque");
        if (chequeNodes.getLength() == 0) chequeNodes = root.getElementsByTagName("Instrument");

        for (int i = 0; i < chequeNodes.getLength(); i++) {
            Element      el = (Element) chequeNodes.item(i);
            ChequeEntity c  = new ChequeEntity();
            c.setBatchId(batchId);

            c.setChequeNo(firstTag(el, "ChequeNo", "InstrNo", "SerialNo", "ChequeNumber"));
            c.setAccountNo(firstTag(el, "AccountNumber", "AccountNo", "AcctNo"));

            String payeeAcct = firstTag(el, "PayeeAccountNumber", "PayeeAcctNo", "PayeeAccountNo");
            if (payeeAcct != null) c.setPayeeAccountNo(payeeAcct);

            c.setSortCode(firstTag(el, "SortCode", "MICRCode"));
            c.setDrawerName(firstTag(el, "DrawerBank", "DrawerName", "Drawer"));
            c.setPayeeName(firstTag(el, "PayeeName", "Payee"));
            c.setChequeDate(firstTag(el, "ChequeDate", "Date"));

            parseMicrLine(c, firstTag(el, "MICRLine"));

            if (isBlank(c.getTransactionCode())) {
                String tc = firstTag(el, "TC", "TransactionCode", "TxCode");
                if (tc != null) c.setTransactionCode(tc);
            }
            if (isBlank(c.getBaseNo())) {
                String baseNo = firstTag(el, "BaseNo", "BaseNumber");
                if (baseNo != null) c.setBaseNo(baseNo);
            }

            String amtWords = firstTag(el, "AmountInWords", "AmtInWords", "AmountWords");
            if (amtWords != null) c.setAmountInWords(amtWords);

            String amtStr = firstTag(el, "Amount", "Amt");
            if (amtStr != null) {
                try { c.setAmount(new BigDecimal(amtStr)); }
                catch (NumberFormatException ignored) {}
            }

            String iqa = firstTag(el, "IQA");
            c.setIqaStatus(iqa != null ? iqa : "Pass");
            c.setVerStatus(ChequeStatus.PENDING.db());
            c.setStatus(ChequeStatus.PENDING.db());
            c.setCreatedAt(LocalDateTime.now());
            c.setUpdatedAt(LocalDateTime.now());
            list.add(c);
        }

        LOG.info("Master XML parsed: " + list.size() + " cheques");
        return list;
    }

    // ══════════════════════════════════════════════════════════════════
    // MICR LINE PARSER
    // Handles: "123456 110532005 20000123456789 29"
    //  tokens:   [0]ChequeNo [1]SortCode [2]BaseNo [3]TC
    // ══════════════════════════════════════════════════════════════════

    private void parseMicrLine(ChequeEntity c, String micrLine) {
        if (micrLine == null || micrLine.isBlank()) return;

        // Strip MICR special characters (⑆ ⑇ ⑈ ⑉) sometimes present in OCR output
        String clean  = micrLine.replaceAll("[^\\d\\s]", "").trim();
        String[] parts = clean.split("\\s+");

        LOG.info("MICR line parsed — raw: [" + micrLine + "] clean tokens: " + parts.length);

        if (parts.length >= 4) {
            // Standard 4-token format: ChequeNo SortCode BaseNo TC
            c.setTransactionCode(parts[parts.length - 1]);       // TC  = last token  → "29"
            c.setBaseNo(parts[parts.length - 2]);                // BaseNo = 2nd last → "20000123456789"
            // parts[0] = ChequeNo  (already set from XML tag — don't override)
            // parts[1] = SortCode  (already set from XML tag — don't override)
        } else if (parts.length == 3) {
            // 3-token: SortCode BaseNo TC  (chequeNo absent)
            c.setTransactionCode(parts[2]);
            c.setBaseNo(parts[1]);
        } else if (parts.length == 2) {
            // 2-token: BaseNo TC
            c.setTransactionCode(parts[1]);
            c.setBaseNo(parts[0]);
        } else if (parts.length == 1) {
            c.setTransactionCode(parts[0]);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ZIP EXTRACTION
    // ══════════════════════════════════════════════════════════════════

    private Map<String, byte[]> extractZipEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                String              name = entry.getName().replace("\\", "/");
                ByteArrayOutputStream buf  = new ByteArrayOutputStream();
                byte[] block = new byte[8192]; int read;
                while ((read = zis.read(block)) != -1) buf.write(block, 0, read);
                map.put(name, buf.toByteArray());
                zis.closeEntry();
            }
        }
        return map;
    }

    private Map<String, Map<String, byte[]>> groupByFolder(Map<String, byte[]> entries) {
        Map<String, Map<String, byte[]>> folders = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String   path   = e.getKey();
            String[] parts  = path.split("/");
            if (parts.length < 2) continue;
            String folderName = parts[parts.length - 2];
            String fileName   = parts[parts.length - 1];
            folders.computeIfAbsent(folderName, k -> new LinkedHashMap<>()).put(fileName, e.getValue());
        }
        return folders;
    }

    private byte[] findXmlInFolder(Map<String, byte[]> folderFiles) {
        for (Map.Entry<String, byte[]> e : folderFiles.entrySet())
            if (e.getKey().toLowerCase().endsWith(".xml")) return e.getValue();
        return null;
    }

    private byte[] findImage(Map<String, byte[]> folderFiles, boolean front) {
        for (Map.Entry<String, byte[]> e : folderFiles.entrySet()) {
            String name = e.getKey().toLowerCase();
            if (!isImage(name)) continue;
            if (front  &&  isFrontImageFile(name)) return e.getValue();
            if (!front && !isFrontImageFile(name)) return e.getValue();
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // FILENAME HELPERS
    // ══════════════════════════════════════════════════════════════════

    private boolean isImage(String lowerName) {
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
            || lowerName.endsWith(".png") || lowerName.endsWith(".tiff")
            || lowerName.endsWith(".tif");
    }

    private boolean isFrontImageFile(String lowerName) {
        if (lowerName.contains("front")) return true;
        if (lowerName.contains("rear"))  return false;
        if (lowerName.matches(".*_f\\..*")) return true;
        if (lowerName.matches(".*_r\\..*")) return false;
        if (lowerName.startsWith("f"))   return true;
        if (lowerName.startsWith("r"))   return false;
        return true; // default to front
    }

    private String extractChequeNoFromFilename(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.'); if (dot >= 0) base = base.substring(0, dot);
        if (base.toLowerCase().startsWith("front_") || base.toLowerCase().startsWith("rear_")) {
            String num = base.substring(base.indexOf('_') + 1);
            if (num.matches("\\d+")) return num;
        }
        if (base.matches("\\d+_[FRfr]")) return base.substring(0, base.lastIndexOf('_'));
        if (base.matches("[FRfr]\\d+"))  return base.substring(1);
        if (base.matches("\\d+"))        return base;
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // XML DOM HELPERS
    // ══════════════════════════════════════════════════════════════════

    private Document buildDoc(InputStream is) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(is);
        doc.getDocumentElement().normalize();
        return doc;
    }

    private String firstTag(Element parent, String... tagNames) {
        for (String tag : tagNames) {
            NodeList nl = parent.getElementsByTagName(tag);
            if (nl.getLength() > 0) {
                String v = nl.item(0).getTextContent();
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // BATCH BUILDERS
    // ══════════════════════════════════════════════════════════════════

    private String buildBatchId(String zipName) {
        if (zipName != null) {
            String base = zipName.replace(".zip", "").replace(".ZIP", "").trim();
            if (base.toUpperCase().startsWith("BATCH") && base.length() <= 20)
                return base.toUpperCase();
        }
        return "BATCH" + String.format("%04d", SEQ.getAndIncrement());
    }

    private BatchEntity buildBatchEntity(String batchId) {
        BatchEntity b = new BatchEntity();
        b.setBatchId(batchId);
        b.setBranchCode("MUM01");
        b.setStatus("Processing");
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        return b;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}