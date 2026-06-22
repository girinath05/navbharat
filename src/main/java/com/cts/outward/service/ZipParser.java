/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : ZipParser.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Low-level ZIP parsing utility that converts raw ZIP bytes into
 *  a structured ParseResult (BatchEntity + List<ChequeEntity>).
 *
 *  This class handles two physical ZIP structures produced by
 *  different scanning software vendors (Structure A and B below),
 *  auto-detecting which structure is present and routing to the
 *  appropriate parsing path.
 *
 * ──────────────────────────────────────────────────────────────
 *  SUPPORTED ZIP STRUCTURES
 * ──────────────────────────────────────────────────────────────
 *
 *  STRUCTURE A — Folder-per-cheque (multiple XML files):
 *  ───────────────────────────────────────────────────────
 *  MUM01_20260610.zip
 *  ├── 000001/
 *  │   ├── cheque.xml        ← per-cheque metadata
 *  │   ├── front.jpg
 *  │   └── rear.jpg
 *  ├── 000002/
 *  │   ├── cheque.xml
 *  │   ├── F000002.tif
 *  │   └── R000002.tif
 *  └── ...
 *
 *  DETECTION: xmlCount > 1  →  parseFolderPerCheque()
 *
 *  STRUCTURE B — Flat batch XML (single XML file):
 *  ─────────────────────────────────────────────────
 *  MUM01_20260610.zip
 *  ├── batch.xml             ← all cheques inside one master XML
 *  ├── front_000001.jpg
 *  ├── rear_000001.jpg
 *  ├── front_000002.jpg
 *  └── rear_000002.jpg
 *
 *  DETECTION: xmlCount == 1  →  parseFlatBatch()
 *
 * ──────────────────────────────────────────────────────────────
 *  CALL FLOW — WHERE THIS CLASS FITS IN THE PIPELINE
 * ──────────────────────────────────────────────────────────────
 *
 *  BatchChequeEntryComposer
 *    onZipUpload()  /  onScanZipUpload()
 *      │
 *      ▼
 *  ZipImportServiceImpl.importZip(zipBytes, zipName, ...)
 *      │
 *      ├──► [DEPRECATED PATH] ZipParser.parse(zipBytes, zipName)    ← THIS CLASS
 *      │         (legacy static utility; being replaced by CtsZipParserImpl)
 *      │
 *      └──► [CURRENT PATH]   CtsZipParserImpl.parse(zipBytes, zipName)
 *               (implements CtsParser interface; preferred over ZipParser)
 *
 *  NOTE: ZipParser is the original monolithic parser. The project is
 *  progressively migrating to the CtsParser / CtsZipParserImpl interface
 *  hierarchy for better testability and separation of concerns.
 *  ZipParser remains in the codebase as a reference and fallback.
 *
 *  ParseResult flows upward:
 *    ZipParser.parse()
 *      → ZipImportServiceImpl (dedup + persist)
 *        → ImportResult
 *          → BatchChequeEntryComposer (UI decision: success / mismatch / all-dups)
 *
 * ──────────────────────────────────────────────────────────────
 *  MICR / RBI CTS-2010 FIELD MAPPING
 * ──────────────────────────────────────────────────────────────
 *  MICRLine format (space-separated):
 *    <ChequeNo> <BankCode> <BranchCode> <TransactionCode>
 *  TransactionCode is always the LAST token — parsed with split+last.
 *
 *  XML tag aliases handled (vendor variations):
 *    ChequeNumber / ChequeNo / InstrNo
 *    AccountNumber / AccountNo / AcctNo
 *    SortCode / MICRCode / MicrCode
 *    AmountInWords / AmtInWords / AmountWords
 *    Amount / Amt
 *    IQA / IqaStatus
 * ============================================================
 */

package com.cts.outward.service;

import com.cts.outward.enums.BatchStatus;
import com.cts.outward.enums.ChequeStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;

/**
 * Static utility class that parses a raw CTS ZIP file into a {@link ParseResult}.
 *
 * <p>Supports two ZIP structures (folder-per-cheque and flat-batch) and
 * auto-detects which format is in use based on the number of XML files found.
 *
 * <p><b>Thread safety:</b> All methods are static and stateless except for the
 * {@code SEQ} counter used for batch ID generation, which uses an
 * {@link AtomicInteger} and is safe for concurrent access.
 *
 * @author Umesh M.
 * @see CtsZipParserImpl   (current interface-based replacement)
 * @see ZipImportServiceImpl
 * @see ParseResult
 */
public class ZipParser {

    private static final Logger LOG = Logger.getLogger(ZipParser.class.getName());

    // ══════════════════════════════════════════════════════════════════
    // ENTRY POINT — PUBLIC PARSE METHOD
    // ══════════════════════════════════════════════════════════════════

    /**
     * Parses a CTS ZIP file and returns a structured result containing the
     * batch entity and all cheque entities extracted from it.
     *
     * <p><b>Called by:</b> {@code ZipImportServiceImpl.importZip()} — immediately
     * after receiving raw ZIP bytes from the ZK {@code UploadEvent}.
     *
     * <h3>Processing pipeline</h3>
     * <ol>
     *   <li>Decompress all entries from the ZIP into an in-memory map
     *       (entry name → raw bytes)</li>
     *   <li>Detect ZIP structure: multiple XML files → Structure A (folder-per-cheque);
     *       single XML file → Structure B (flat batch XML)</li>
     *   <li>Route to the appropriate parsing path</li>
     *   <li>Return {@link ParseResult} with populated batch and cheque entities</li>
     * </ol>
     *
     * @param zipBytes raw bytes of the uploaded ZIP file from ZK {@code UploadEvent}
     * @param zipName  original filename (e.g. {@code MUM01_20260610.zip});
     *                 used for batch ID derivation and logging
     * @return {@link ParseResult} containing the batch entity and all parsed cheques
     * @throws RuntimeException wrapping any IO or XML parse failure
     */
    public static ParseResult parse(byte[] zipBytes, String zipName) {
        LOG.info("ZipParser.parse() — file: " + zipName + " | size: " + zipBytes.length + " bytes");
        try {
            // Step 1: decompress all ZIP entries into memory
            Map<String, byte[]> entryNameToBytes = extractZipEntries(zipBytes);
            LOG.info("ZIP entries found: " + entryNameToBytes.keySet());

            // Step 2: detect structure from number of XML files
            boolean isFolderPerCheque = detectFolderPerCheque(entryNameToBytes);
            LOG.info("ZIP structure: "
                    + (isFolderPerCheque
                    ? "FOLDER-PER-CHEQUE (Structure A)"
                    : "FLAT BATCH XML (Structure B)"));

            // Step 3: route to correct parser
            if (isFolderPerCheque) {
                return parseFolderPerCheque(entryNameToBytes, zipName);
            } else {
                return parseFlatBatch(entryNameToBytes, zipName);
            }

        } catch (Exception ex) {
            LOG.severe("ZIP parse failed: " + ex.getMessage());
            throw new RuntimeException("Failed to parse ZIP: " + ex.getMessage(), ex);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STRUCTURE DETECTION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Detects ZIP structure by counting XML files.
     *
     * <p>More than one XML file means each cheque has its own subfolder
     * with its own XML (Structure A). Exactly one XML means a single
     * master XML covers all cheques (Structure B).
     *
     * @param entryNameToBytes map of all ZIP entries (name → bytes)
     * @return {@code true} if Structure A (folder-per-cheque); {@code false} if Structure B
     */
    private static boolean detectFolderPerCheque(Map<String, byte[]> entryNameToBytes) {
        long xmlFileCount = entryNameToBytes.keySet().stream()
                .filter(entryName -> entryName.toLowerCase().endsWith(".xml"))
                .count();
        return xmlFileCount > 1;
    }

    // ══════════════════════════════════════════════════════════════════
    // STRUCTURE A — FOLDER-PER-CHEQUE PARSER
    // ══════════════════════════════════════════════════════════════════

    /**
     * Parses a Structure A ZIP where each cheque occupies its own folder
     * containing one XML file and two image files (front + rear).
     *
     * <h3>Steps</h3>
     * <ol>
     *   <li>Group all ZIP entries by their parent folder name</li>
     *   <li>For each folder: find the XML, parse it into a {@link ChequeEntity},
     *       then locate and attach front and rear images</li>
     *   <li>Build a {@link BatchEntity} with aggregate totals</li>
     * </ol>
     *
     * @param entryNameToBytes all ZIP entries (name → bytes)
     * @param zipName          original ZIP filename for batch ID derivation
     * @return {@link ParseResult} with batch and per-cheque entities
     */
    private static ParseResult parseFolderPerCheque(Map<String, byte[]> entryNameToBytes,
                                                     String zipName) throws Exception {
        // Group entries: folderName → (fileName → bytes)
        Map<String, Map<String, byte[]>> chequesFolderMap = groupByFolder(entryNameToBytes);
        LOG.info("Cheque folders detected: " + chequesFolderMap.keySet());

        String batchId  = buildBatchId(zipName);
        BatchEntity batch = buildBatchEntity(batchId);

        List<ChequeEntity> parsedCheques = new ArrayList<>();
        BigDecimal batchTotalAmount = BigDecimal.ZERO;

        for (Map.Entry<String, Map<String, byte[]>> folderEntry : chequesFolderMap.entrySet()) {
            String              folderName  = folderEntry.getKey();
            Map<String, byte[]> folderFiles = folderEntry.getValue();

            // Each folder must have exactly one XML file
            byte[] chequeXmlBytes = findXmlInFolder(folderFiles);
            if (chequeXmlBytes == null) {
                LOG.warning("No XML in folder: " + folderName + " — skipping");
                continue;
            }

            // Parse per-cheque XML into entity
            ChequeEntity parsedCheque = parsePerChequeXml(
                    new ByteArrayInputStream(chequeXmlBytes), batchId, folderName);
            if (parsedCheque == null) {
                LOG.warning("Failed to parse cheque XML in folder: " + folderName);
                continue;
            }

            // Attach front and rear images
            parsedCheque.setFrontImage(findImage(folderFiles, true));
            parsedCheque.setRearImage(findImage(folderFiles, false));

            parsedCheques.add(parsedCheque);

            // Accumulate batch total (null-safe)
            if (parsedCheque.getAmount() != null) {
                batchTotalAmount = batchTotalAmount.add(parsedCheque.getAmount());
            }

            LOG.info("  Parsed cheque: " + parsedCheque.getChequeNo()
                    + " | Amount: " + parsedCheque.getAmount());
        }

        // Populate batch-level aggregate fields
        batch.setTotalCheques(parsedCheques.size());
        batch.setTotalAmount(batchTotalAmount);
        batch.setStatus(BatchStatus.DRAFT.db());
        batch.setUpdatedAt(LocalDateTime.now());

        return new ParseResult(batch, parsedCheques);
    }

    // ══════════════════════════════════════════════════════════════════
    // STRUCTURE B — FLAT BATCH XML PARSER
    // ══════════════════════════════════════════════════════════════════

    /**
     * Parses a Structure B ZIP where a single master XML file lists all cheques,
     * and image files are stored flat (no subfolders).
     *
     * <h3>Steps</h3>
     * <ol>
     *   <li>Find the single XML file in the ZIP</li>
     *   <li>Parse all {@code <Cheque>} or {@code <Instrument>} elements from it</li>
     *   <li>For each parsed cheque, find its front/rear images by matching the
     *       cheque number in the image filename</li>
     *   <li>Build a {@link BatchEntity} with aggregate totals</li>
     * </ol>
     *
     * @param entryNameToBytes all ZIP entries (name → bytes)
     * @param zipName          original ZIP filename for batch ID derivation
     * @return {@link ParseResult} with batch and all cheque entities
     */
    private static ParseResult parseFlatBatch(Map<String, byte[]> entryNameToBytes,
                                               String zipName) throws Exception {
        String batchId    = buildBatchId(zipName);
        BatchEntity batch = buildBatchEntity(batchId);

        // Locate the single XML file (first match wins)
        byte[] masterXmlBytes = null;
        for (Map.Entry<String, byte[]> entry : entryNameToBytes.entrySet()) {
            if (entry.getKey().toLowerCase().endsWith(".xml")) {
                masterXmlBytes = entry.getValue();
                break;
            }
        }
        if (masterXmlBytes == null) {
            throw new RuntimeException("No XML file found in ZIP");
        }

        // Parse all cheque records from master XML
        List<ChequeEntity> parsedCheques = parseMasterXml(
                new ByteArrayInputStream(masterXmlBytes), batchId, batch);

        // Match flat image files to cheques by extracting cheque number from filename
        for (ChequeEntity parsedCheque : parsedCheques) {
            String chequeNo = parsedCheque.getChequeNo();
            if (chequeNo == null) continue;

            for (Map.Entry<String, byte[]> imageEntry : entryNameToBytes.entrySet()) {
                String entryNameLower = imageEntry.getKey().toLowerCase();
                if (!isImage(entryNameLower)) continue;

                String detectedChequeNo = extractChequeNoFromFilename(imageEntry.getKey());
                if (!chequeNo.equals(detectedChequeNo)) continue;

                if (isFrontImageFile(entryNameLower)) {
                    parsedCheque.setFrontImage(imageEntry.getValue());
                } else {
                    parsedCheque.setRearImage(imageEntry.getValue());
                }
            }
        }

        // Populate batch-level aggregate fields (null-safe sum)
        BigDecimal batchTotalAmount = parsedCheques.stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        batch.setTotalCheques(parsedCheques.size());
        batch.setTotalAmount(batchTotalAmount);
        batch.setStatus(BatchStatus.DRAFT.db());
        batch.setUpdatedAt(LocalDateTime.now());

        return new ParseResult(batch, parsedCheques);
    }

    // ══════════════════════════════════════════════════════════════════
    // XML PARSERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Parses a single per-cheque XML file (Structure A) into a {@link ChequeEntity}.
     *
     * <p>Handles multiple vendor XML tag aliases for each field.
     * TransactionCode is derived from the last whitespace-separated token
     * of the {@code <MICRLine>} element, per RBI CTS-2010 spec.
     *
     * <p><b>Called by:</b> {@link #parseFolderPerCheque}
     *
     * @param xmlStream   stream of the per-cheque XML bytes
     * @param batchId     batch ID to assign to the cheque entity
     * @param folderName  folder name used as fallback cheque ID and for logging
     * @return populated {@link ChequeEntity}, or {@code null} if XML parse fails
     */
    private static ChequeEntity parsePerChequeXml(InputStream xmlStream,
                                                   String batchId,
                                                   String folderName) {
        try {
            Document xmlDoc  = buildXmlDocument(xmlStream);
            Element  rootEl  = xmlDoc.getDocumentElement();

            ChequeEntity cheque = new ChequeEntity();
            cheque.setBatchId(batchId);

            // ── Core identity fields ──────────────────────────────────
            cheque.setChequeNo(firstTag(rootEl, "ChequeNumber", "ChequeNo", "InstrNo"));
            cheque.setAccountNo(firstTag(rootEl, "AccountNumber", "AccountNo", "AcctNo"));
            cheque.setDrawerName(firstTag(rootEl, "DrawerName", "Drawer"));
            cheque.setPayeeName(firstTag(rootEl, "PayeeName", "Payee"));
            cheque.setChequeDate(firstTag(rootEl, "ChequeDate", "Date"));
            cheque.setSortCode(firstTag(rootEl, "SortCode", "MICRCode", "MicrCode"));

            // ── ChequeId — use folder name as fallback ───────────────
            String chequeId = firstTag(rootEl, "ChequeId", "ChequeID");
            cheque.setChequeId(chequeId != null ? chequeId : folderName);

            // ── TransactionCode — RBI CTS-2010: last token of MICRLine ─
            // MICRLine format: "<ChequeNo> <BankCode> <BranchCode> <TC>"
            String micrLine = firstTag(rootEl, "MICRLine");
            if (micrLine != null) {
                String[] micrTokens = micrLine.trim().split("\\s+");
                if (micrTokens.length >= 4) {
                    cheque.setTransactionCode(micrTokens[micrTokens.length - 1]);
                } else if (micrTokens.length == 3) {
                    cheque.setTransactionCode(micrTokens[2]);
                }
            }
            // Fallback: look for explicit TC element if MICRLine was absent/short
            if (cheque.getTransactionCode() == null || cheque.getTransactionCode().isBlank()) {
                String explicitTc = firstTag(rootEl, "TC", "TransactionCode", "TxCode");
                if (explicitTc != null) {
                    cheque.setTransactionCode(explicitTc);
                }
            }

            // ── Amount in words (for mismatch detection later) ────────
            String amountInWords = firstTag(rootEl, "AmountInWords", "AmtInWords", "AmountWords");
            if (amountInWords != null) {
                cheque.setAmountInWords(amountInWords);
            }

            // ── Amount in digits ──────────────────────────────────────
            String amountStr = firstTag(rootEl, "Amount", "Amt");
            if (amountStr != null) {
                try {
                    cheque.setAmount(new BigDecimal(amountStr));
                } catch (NumberFormatException nfe) {
                    LOG.warning("Unparseable amount in folder " + folderName + ": " + amountStr);
                }
            }

            // ── IQA status — default to Pass if absent ────────────────
            String iqaStatus = firstTag(rootEl, "IQA", "IqaStatus");
            cheque.setIqaStatus(iqaStatus != null ? iqaStatus : "Pass");

            // ── Lifecycle status ──────────────────────────────────────
            cheque.setVerStatus(ChequeStatus.PENDING.db());
            cheque.setStatus(ChequeStatus.PENDING.db());

            // ── Fallback cheque number = folder name ─────────────────
            if (cheque.getChequeNo() == null || cheque.getChequeNo().isBlank()) {
                cheque.setChequeNo(folderName);
            }

            // ── Audit timestamps ──────────────────────────────────────
            cheque.setCreatedAt(LocalDateTime.now());
            cheque.setUpdatedAt(LocalDateTime.now());

            return cheque;

        } catch (Exception ex) {
            LOG.severe("parsePerChequeXml error in folder " + folderName + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Parses the single master XML file (Structure B) that contains all cheques
     * under {@code <Cheque>} or {@code <Instrument>} child elements.
     *
     * <p>If the master XML contains a {@code <BatchId>} element, it overrides
     * the ZIP-derived batch ID in the provided {@link BatchEntity}.
     *
     * <p><b>Called by:</b> {@link #parseFlatBatch}
     *
     * @param xmlStream stream of the master XML bytes
     * @param batchId   ZIP-derived batch ID (may be overridden by XML BatchId element)
     * @param batch     batch entity to mutate if XML contains a BatchId element
     * @return list of all {@link ChequeEntity} objects parsed from the XML
     */
    private static List<ChequeEntity> parseMasterXml(InputStream xmlStream,
                                                      String batchId,
                                                      BatchEntity batch) throws Exception {
        List<ChequeEntity> parsedCheques = new ArrayList<>();
        Document xmlDoc = buildXmlDocument(xmlStream);
        Element  rootEl = xmlDoc.getDocumentElement();

        // Override batch ID if the XML explicitly declares one
        String xmlBatchId = firstTag(rootEl, "BatchId", "BatchID");
        if (xmlBatchId != null && !xmlBatchId.isBlank()) {
            batch.setBatchId(xmlBatchId);
            batchId = xmlBatchId;
        }

        // Support both <Cheque> and <Instrument> element names (vendor variation)
        NodeList chequeElements = rootEl.getElementsByTagName("Cheque");
        if (chequeElements.getLength() == 0) {
            chequeElements = rootEl.getElementsByTagName("Instrument");
        }

        for (int i = 0; i < chequeElements.getLength(); i++) {
            Element chequeEl = (Element) chequeElements.item(i);
            ChequeEntity cheque = new ChequeEntity();
            cheque.setBatchId(batchId);

            // ── Core identity fields ──────────────────────────────────
            cheque.setChequeNo(firstTag(chequeEl, "ChequeNo", "InstrNo", "SerialNo", "ChequeNumber"));
            cheque.setAccountNo(firstTag(chequeEl, "AccountNo", "AccountNumber", "AcctNo"));
            cheque.setSortCode(firstTag(chequeEl, "SortCode", "MICRCode"));
            cheque.setDrawerName(firstTag(chequeEl, "DrawerBank", "DrawerName", "Drawer"));
            cheque.setPayeeName(firstTag(chequeEl, "PayeeName", "Payee"));
            cheque.setChequeDate(firstTag(chequeEl, "ChequeDate", "Date"));

            // ── TransactionCode — RBI CTS-2010: last token of MICRLine ─
            String micrLine = firstTag(chequeEl, "MICRLine");
            if (micrLine != null) {
                String[] micrTokens = micrLine.trim().split("\\s+");
                if (micrTokens.length >= 4) {
                    cheque.setTransactionCode(micrTokens[micrTokens.length - 1]);
                } else if (micrTokens.length == 3) {
                    cheque.setTransactionCode(micrTokens[2]);
                }
            }
            // Fallback: look for explicit TC element
            if (cheque.getTransactionCode() == null || cheque.getTransactionCode().isBlank()) {
                String explicitTc = firstTag(chequeEl, "TC", "TransactionCode", "TxCode");
                if (explicitTc != null) {
                    cheque.setTransactionCode(explicitTc);
                }
            }

            // ── Amount in words ───────────────────────────────────────
            String amountInWords = firstTag(chequeEl, "AmountInWords", "AmtInWords", "AmountWords");
            if (amountInWords != null) {
                cheque.setAmountInWords(amountInWords);
            }

            // ── Amount in digits ──────────────────────────────────────
            String amountStr = firstTag(chequeEl, "Amount", "Amt");
            if (amountStr != null) {
                try {
                    cheque.setAmount(new BigDecimal(amountStr));
                } catch (NumberFormatException ignored) {
                    // Non-critical: amount stays null; batch total will omit this cheque
                }
            }

            // ── IQA and lifecycle status ──────────────────────────────
            String iqaStatus = firstTag(chequeEl, "IQA");
            cheque.setIqaStatus(iqaStatus != null ? iqaStatus : "Pass");
            cheque.setVerStatus(ChequeStatus.PENDING.db());
            cheque.setStatus(ChequeStatus.PENDING.db());

            // ── Audit timestamps ──────────────────────────────────────
            cheque.setCreatedAt(LocalDateTime.now());
            cheque.setUpdatedAt(LocalDateTime.now());

            parsedCheques.add(cheque);
        }

        LOG.info("Master XML parsed: " + parsedCheques.size() + " cheques");
        return parsedCheques;
    }

    // ══════════════════════════════════════════════════════════════════
    // ZIP DECOMPRESSION HELPERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Decompresses all non-directory entries from a ZIP into an in-memory map.
     *
     * <p>Backslash path separators (Windows ZIP tools) are normalised to forward
     * slashes before being used as map keys.
     *
     * @param zipBytes raw ZIP file bytes
     * @return insertion-ordered map of entry name → raw entry bytes
     * @throws IOException if the ZIP is corrupt or unreadable
     */
    private static Map<String, byte[]> extractZipEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entryMap = new LinkedHashMap<>();

        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipIn.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) {
                    zipIn.closeEntry();
                    continue;
                }
                // Normalise Windows backslash separators
                String normalizedEntryName = zipEntry.getName().replace("\\", "/");

                ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
                byte[] readBlock = new byte[8192];
                int bytesRead;
                while ((bytesRead = zipIn.read(readBlock)) != -1) {
                    entryBuffer.write(readBlock, 0, bytesRead);
                }

                entryMap.put(normalizedEntryName, entryBuffer.toByteArray());
                zipIn.closeEntry();
            }
        }
        return entryMap;
    }

    /**
     * Groups flat ZIP entries into a two-level map: folderName → (fileName → bytes).
     *
     * <p>Entries without a parent directory (depth == 1) are silently skipped,
     * as Structure A always places files inside a named subfolder.
     *
     * @param entryNameToBytes flat map of all ZIP entries
     * @return nested map where outer key = folder name, inner key = bare filename
     */
    private static Map<String, Map<String, byte[]>> groupByFolder(
            Map<String, byte[]> entryNameToBytes) {

        Map<String, Map<String, byte[]>> folderMap = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> entry : entryNameToBytes.entrySet()) {
            String   fullPath  = entry.getKey();
            String[] pathParts = fullPath.split("/");

            // Need at least: folder/file — skip root-level entries
            if (pathParts.length < 2) continue;

            String folderName = pathParts[pathParts.length - 2];
            String fileName   = pathParts[pathParts.length - 1];

            folderMap.computeIfAbsent(folderName, k -> new LinkedHashMap<>())
                     .put(fileName, entry.getValue());
        }
        return folderMap;
    }

    /**
     * Returns the bytes of the first {@code .xml} file found in the given folder map.
     *
     * @param folderFiles map of filename → bytes for one cheque folder
     * @return XML bytes, or {@code null} if no XML file exists in the folder
     */
    private static byte[] findXmlInFolder(Map<String, byte[]> folderFiles) {
        for (Map.Entry<String, byte[]> entry : folderFiles.entrySet()) {
            if (entry.getKey().toLowerCase().endsWith(".xml")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Finds the front or rear image bytes in a cheque folder.
     *
     * <p>Delegates to {@link #isFrontImageFile(String)} for front/rear detection.
     *
     * @param folderFiles map of filename → bytes for one cheque folder
     * @param lookForFront {@code true} to find front image; {@code false} for rear
     * @return image bytes, or {@code null} if the requested image was not found
     */
    private static byte[] findImage(Map<String, byte[]> folderFiles, boolean lookForFront) {
        for (Map.Entry<String, byte[]> entry : folderFiles.entrySet()) {
            String fileNameLower = entry.getKey().toLowerCase();
            if (!isImage(fileNameLower)) continue;

            if (lookForFront && isFrontImageFile(fileNameLower))  return entry.getValue();
            if (!lookForFront && !isFrontImageFile(fileNameLower)) return entry.getValue();
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // IMAGE CLASSIFICATION HELPERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if the filename has an image extension supported
     * by the CTS platform (JPEG, PNG, TIFF).
     *
     * @param fileNameLower lowercase filename or path
     * @return {@code true} if the file is a recognised image format
     */
    static boolean isImage(String fileNameLower) {
        return fileNameLower.endsWith(".jpg")
                || fileNameLower.endsWith(".jpeg")
                || fileNameLower.endsWith(".png")
                || fileNameLower.endsWith(".tiff")
                || fileNameLower.endsWith(".tif");
    }

    /**
     * Determines whether an image filename refers to the front or rear scan.
     *
     * <p>Detection priority (first match wins):
     * <ol>
     *   <li>Contains "front" → front</li>
     *   <li>Contains "rear"  → rear</li>
     *   <li>Matches {@code *_f.*} pattern → front</li>
     *   <li>Matches {@code *_r.*} pattern → rear</li>
     *   <li>Starts with "f" → front</li>
     *   <li>Starts with "r" → rear</li>
     *   <li>Default → front (conservative fallback)</li>
     * </ol>
     *
     * @param fileNameLower lowercase filename (not path)
     * @return {@code true} if classified as front image; {@code false} if rear
     */
    static boolean isFrontImageFile(String fileNameLower) {
        if (fileNameLower.contains("front")) return true;
        if (fileNameLower.contains("rear"))  return false;
        if (fileNameLower.matches(".*_f\\..*")) return true;
        if (fileNameLower.matches(".*_r\\..*")) return false;
        if (fileNameLower.startsWith("f")) return true;
        if (fileNameLower.startsWith("r")) return false;
        return true; // conservative default: assume front
    }

    /**
     * Extracts a cheque number from a flat-structure image filename using
     * vendor naming conventions.
     *
     * <p>Supported patterns:
     * <ul>
     *   <li>{@code front_000001.jpg}  → {@code "000001"}</li>
     *   <li>{@code rear_000001.tif}   → {@code "000001"}</li>
     *   <li>{@code 000001_F.jpg}      → {@code "000001"}</li>
     *   <li>{@code 000001_R.tif}      → {@code "000001"}</li>
     *   <li>{@code F000001.jpg}       → {@code "000001"}</li>
     *   <li>{@code 000001.jpg}        → {@code "000001"}</li>
     * </ul>
     *
     * @param fileName original filename (case-preserved for digit extraction)
     * @return extracted cheque number string, or {@code null} if no pattern matched
     */
    static String extractChequeNoFromFilename(String fileName) {
        // Strip file extension
        String baseName = fileName;
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex >= 0) {
            baseName = baseName.substring(0, dotIndex);
        }

        // Pattern: front_<digits> or rear_<digits>
        String baseNameLower = baseName.toLowerCase();
        if (baseNameLower.startsWith("front_") || baseNameLower.startsWith("rear_")) {
            String numericPart = baseName.substring(baseName.indexOf('_') + 1);
            if (numericPart.matches("\\d+")) return numericPart;
        }

        // Pattern: <digits>_F or <digits>_R
        if (baseName.matches("\\d+_[FRfr]")) {
            return baseName.substring(0, baseName.lastIndexOf('_'));
        }

        // Pattern: F<digits> or R<digits>
        if (baseName.matches("[FRfr]\\d+")) {
            return baseName.substring(1);
        }

        // Pattern: pure digits
        if (baseName.matches("\\d+")) {
            return baseName;
        }

        return null; // no matching pattern found
    }

    // ══════════════════════════════════════════════════════════════════
    // XML DOM HELPERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Builds a parsed {@link Document} from an XML input stream.
     *
     * <p>External entity resolution is disabled to prevent XXE (XML External
     * Entity) injection attacks — critical for a banking system processing
     * externally-sourced ZIP files.
     *
     * @param xmlStream input stream of raw XML bytes
     * @return normalised DOM {@link Document}
     * @throws Exception if the XML is malformed or the parser cannot be initialised
     */
    private static Document buildXmlDocument(InputStream xmlStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        // XXE hardening — disable external entity resolution
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        Document doc = factory.newDocumentBuilder().parse(xmlStream);
        doc.getDocumentElement().normalize();
        return doc;
    }

    /**
     * Returns the trimmed text content of the first matching XML element,
     * trying each tag name alias in order until a non-blank value is found.
     *
     * <p>Used to handle vendor XML variations where the same logical field
     * has different element names in different scanning software outputs
     * (e.g. {@code ChequeNumber} vs {@code ChequeNo} vs {@code InstrNo}).
     *
     * @param parent   DOM element to search within
     * @param tagNames one or more tag name candidates to try in priority order
     * @return trimmed text value of the first matching non-blank element,
     *         or {@code null} if none of the tag names produced a value
     */
    private static String firstTag(Element parent, String... tagNames) {
        for (String tagName : tagNames) {
            NodeList matchingNodes = parent.getElementsByTagName(tagName);
            if (matchingNodes.getLength() > 0) {
                String textValue = matchingNodes.item(0).getTextContent();
                if (textValue != null && !textValue.isBlank()) {
                    return textValue.trim();
                }
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // BATCH ID AND ENTITY HELPERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Thread-safe sequence counter for fallback batch ID generation.
     * Used only when the ZIP filename cannot be used as a batch ID.
     */
    private static final AtomicInteger BATCH_ID_SEQUENCE = new AtomicInteger(1);

    /**
     * Derives a batch ID from the ZIP filename, or generates a sequential
     * fallback ID if the filename is not suitable.
     *
     * <p>Naming rules:
     * <ul>
     *   <li>ZIP name without extension, uppercased, used directly if it starts
     *       with "BATCH" and is ≤ 20 characters (e.g. {@code BATCH0001})</li>
     *   <li>Otherwise: {@code BATCH0001}, {@code BATCH0002}, ... (sequence-based)</li>
     * </ul>
     *
     * @param zipName original ZIP filename (e.g. {@code MUM01_20260610.zip})
     * @return non-null batch ID string, ≤ 20 characters, uppercase
     */
    private static String buildBatchId(String zipName) {
        if (zipName != null) {
            String baseName = zipName
                    .replace(".zip", "")
                    .replace(".ZIP", "")
                    .trim();
            if (baseName.toUpperCase().startsWith("BATCH") && baseName.length() <= 20) {
                return baseName.toUpperCase();
            }
        }
        return "BATCH" + String.format("%04d", BATCH_ID_SEQUENCE.getAndIncrement());
    }

    /**
     * Builds a skeleton {@link BatchEntity} with the given batch ID and
     * placeholder defaults. Aggregate fields (totalCheques, totalAmount)
     * are populated after all cheques are parsed.
     *
     * @param batchId batch ID for the new entity
     * @return partially populated {@link BatchEntity}
     */
    private static BatchEntity buildBatchEntity(String batchId) {
        BatchEntity batch = new BatchEntity();
        batch.setBatchId(batchId);
        batch.setBranchCode("MUM01");          // overwritten by composer from session
        batch.setStatus("Processing");          // temporary; overwritten after parse
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        return batch;
    }

    // ══════════════════════════════════════════════════════════════════
    // PARSE RESULT — INNER VALUE OBJECT
    // ══════════════════════════════════════════════════════════════════

    /**
     * Immutable result of a single {@link ZipParser#parse} call.
     *
     * <p>Carries the parsed {@link BatchEntity} and an unmodifiable list of
     * {@link ChequeEntity} objects. Consumed by {@link ZipImportServiceImpl}
     * for duplicate filtering and persistence.
     *
     * <h3>Flow</h3>
     * <pre>
     *   ZipParser.parse(bytes, name)
     *     → returns ParseResult
     *       → ZipImportServiceImpl deduplicates + persists
     *         → ImportResult returned to BatchChequeEntryComposer
     * </pre>
     */
    public static class ParseResult {

        /** The batch entity with aggregate fields (totalCheques, totalAmount, status). */
        private final BatchEntity         batch;

        /**
         * Unmodifiable list of all cheque entities parsed from the ZIP.
         * ZipImportServiceImpl will filter this list for duplicates before persisting.
         */
        private final List<ChequeEntity>  cheques;

        /**
         * Constructs a ParseResult and wraps the cheque list as unmodifiable
         * to prevent accidental mutation after parsing.
         *
         * @param batch   populated batch entity
         * @param cheques list of parsed cheque entities (will be made unmodifiable)
         */
        public ParseResult(BatchEntity batch, List<ChequeEntity> cheques) {
            this.batch   = batch;
            this.cheques = Collections.unmodifiableList(cheques);
        }

        /**
         * Returns the batch entity derived from the ZIP file.
         *
         * @return non-null {@link BatchEntity}
         */
        public BatchEntity getBatch() {
            return batch;
        }

        /**
         * Returns the unmodifiable list of cheque entities parsed from the ZIP.
         *
         * @return unmodifiable list; may be empty if no cheques were found
         */
        public List<ChequeEntity> getCheques() {
            return cheques;
        }

        /**
         * Convenience method returning the number of cheques parsed.
         *
         * @return size of the cheques list
         */
        public int totalCheques() {
            return cheques.size();
        }

        /**
         * Computes the sum of all parsed cheque amounts (null-safe).
         *
         * @return total amount across all cheques; {@link BigDecimal#ZERO} if all amounts are null
         */
        public BigDecimal totalAmount() {
            return cheques.stream()
                    .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /**
         * Returns a human-readable summary of this parse result for logging.
         *
         * @return string like {@code ParseResult{batchId=BATCH0001, cheques=5, total=₹25000.00}}
         */
        @Override
        public String toString() {
            return "ParseResult{batchId=" + batch.getBatchId()
                    + ", cheques=" + cheques.size()
                    + ", total=₹" + totalAmount() + "}";
        }
    }
}