/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : ZipProcessingServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Concrete implementation of {@link ZipProcessingService}.
 *
 *  Responsibilities:
 *    1. Extract all entries from a CTS ZIP file into an in-memory
 *       name → bytes map (directories skipped).
 *    2. Locate and parse the embedded XML file via CtsXmlParser
 *       to produce a List<ChequeModel> with MICR data.
 *    3. Build a chequeNo → ChequeModel index for O(1) image
 *       association without nested loops.
 *    4. Iterate image entries, derive chequeNo and front/rear
 *       flag from the filename using supported naming conventions,
 *       and attach raw image bytes to the matching ChequeModel.
 *    5. Add cheques to the BatchModel, recalculate totals, and
 *       return the populated model to the caller.
 *
 * ──────────────────────────────────────────────────────────────
 *  ZIP STRUCTURE EXPECTED
 * ──────────────────────────────────────────────────────────────
 *  A valid CTS ZIP contains exactly one XML file and one or more
 *  image files. Sub-directories are allowed but ignored:
 *
 *    MUM01_20260610.zip
 *    ├── cheques.xml           ← parsed by CtsXmlParser
 *    ├── front_123456.jpg      ← front image for cheque 123456
 *    ├── rear_123456.jpg       ← rear  image for cheque 123456
 *    ├── front_123457.jpg
 *    └── rear_123457.jpg
 *
 * ──────────────────────────────────────────────────────────────
 *  IMAGE FILENAME CONVENTIONS SUPPORTED
 * ──────────────────────────────────────────────────────────────
 *  extractChequeNo() and isFrontImage() together handle four
 *  naming patterns used by different scanner vendors:
 *
 *    Pattern                 chequeNo    Front?
 *    ──────────────────────  ──────────  ──────
 *    front_{chequeNo}.jpg    chequeNo    Yes
 *    rear_{chequeNo}.jpg     chequeNo    No
 *    {chequeNo}_f.jpg        chequeNo    Yes
 *    {chequeNo}_r.jpg        chequeNo    No
 *    {chequeNo}.jpg          chequeNo    Yes (default)
 *
 *  Images that do not match any pattern are logged and skipped.
 *
 * ──────────────────────────────────────────────────────────────
 *  BATCH ID GENERATION — IN-MEMORY vs DB-DRIVEN
 * ──────────────────────────────────────────────────────────────
 *  BATCH_SEQ is an in-memory AtomicInteger, NOT the DB sequence
 *  used by BatchServiceImpl. This is intentional for the legacy
 *  path: the caller may override the batchId before persisting,
 *  or BatchServiceImpl.createBatch() generates the real DB ID
 *  in the two-step scan workflow.
 *
 *  ⚠ Do not use this in-memory ID as the final DB batchId in
 *  production without verifying no collision with the DB max seq.
 *
 * ──────────────────────────────────────────────────────────────
 *  PERSISTENCE RESPONSIBILITY
 * ──────────────────────────────────────────────────────────────
 *  This class does NOT persist anything to the DB. It returns
 *  a BatchModel; the caller (ZipImportServiceImpl or a composer)
 *  is responsible for deduplication, entity conversion, and
 *  DAO calls.
 *
 * ──────────────────────────────────────────────────────────────
 *  INSTANTIATION
 * ──────────────────────────────────────────────────────────────
 *  new ZipProcessingServiceImpl(new CtsXmlParserImpl())
 * ============================================================
 */

package com.cts.outward.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.cts.outward.enums.BatchStatus;
import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;
import com.cts.outward.parser.CtsXmlParser;

/**
 * Concrete implementation of {@link ZipProcessingService}.
 *
 * <p>Extracts, parses, and associates all content from a CTS ZIP file into a
 * fully populated {@link BatchModel}. Does not touch the DB — persistence is
 * the caller's responsibility. See class-level Javadoc for ZIP structure
 * expectations, image naming conventions, and batch ID generation notes.
 *
 * @author Umesh M.
 * @see ZipProcessingService
 * @see com.cts.outward.parser.CtsXmlParser
 */
public class ZipProcessingServiceImpl implements ZipProcessingService {

    /** Logger for ZIP entry counts, XML parse events, and skipped image warnings. */
    private static final Logger LOG = Logger.getLogger(ZipProcessingServiceImpl.class.getName());

    /**
     * In-memory batch sequence counter for batchId generation.
     * Starts at 1 per JVM lifetime; resets on server restart.
     *
     * <p><b>Note:</b> This is NOT the DB-authoritative sequence.
     * See class-level Javadoc "BATCH ID GENERATION" section.
     */
    private static final AtomicInteger BATCH_SEQ = new AtomicInteger(1);

    // ══════════════════════════════════════════════════════════════════════
    // DEPENDENCY (injected via constructor)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * XML parser that reads the cheque data XML embedded in the ZIP and
     * produces a {@code List<ChequeModel>} — one per cheque element.
     * Implementation: {@code CtsXmlParserImpl}.
     */
    private final CtsXmlParser xmlParser;

    // ══════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Constructs a ZipProcessingServiceImpl with the required XML parser.
     *
     * <p>Standard usage:
     * <pre>
     *   ZipProcessingService service =
     *       new ZipProcessingServiceImpl(new CtsXmlParserImpl());
     * </pre>
     *
     * @param xmlParser CTS cheque XML parser implementation
     */
    public ZipProcessingServiceImpl(CtsXmlParser xmlParser) {
        this.xmlParser = xmlParser;
    }

    // ══════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Processes a CTS ZIP file through the full extract → parse →
     * image-associate → recalculate pipeline and returns a populated
     * {@link BatchModel}.
     *
     * <h3>Pipeline stages</h3>
     * <ol>
     *   <li><b>Generate batch ID</b> — in-memory sequence (see class-level note)</li>
     *   <li><b>Extract ZIP</b> — all entries to name→bytes map via
     *       {@link #extractAllEntries(byte[])}</li>
     *   <li><b>Parse XML</b> — first {@code .xml} entry parsed by
     *       {@code CtsXmlParser} → {@code List<ChequeModel>}</li>
     *   <li><b>Build index</b> — chequeNo → ChequeModel for O(1) image lookup</li>
     *   <li><b>Associate images</b> — iterate image entries, derive chequeNo +
     *       front/rear flag, attach bytes to matching ChequeModel</li>
     *   <li><b>Recalculate</b> — {@code batch.recalculate()} sets totalCheques
     *       and totalAmount from the assembled cheque list</li>
     *   <li><b>Return</b> BatchModel — caller persists or renders</li>
     * </ol>
     *
     * <h3>Called by</h3>
     * {@code BatchChequeEntryComposer} legacy upload path and integration tests.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchChequeEntryComposer (legacy path)
     *   → ZipProcessingServiceImpl.processZip(zipBytes, zipName)
     *
     *       → generateBatchId()                     [BATCH0001, BATCH0002 …]
     *       → new BatchModel(batchId, "MUM01")
     *
     *       → extractAllEntries(zipBytes)
     *           → ZipInputStream loop → name→bytes LinkedHashMap
     *
     *       → for each entry: find first .xml file
     *           → CtsXmlParser.parse(xmlInputStream, batchId)
     *               → returns List&lt;ChequeModel&gt;
     *
     *       → build chequeNo→ChequeModel index
     *
     *       → for each image entry (.jpg/.jpeg/.png/.tiff):
     *           → extractChequeNo(baseName)     [derive chequeNo from filename]
     *           → isFrontImage(baseName)         [true=front, false=rear, null=skip]
     *           → chequeModel.setFrontImageBytes() OR setRearImageBytes()
     *
     *       → batch.getCheques().addAll(cheques)
     *       → batch.recalculate()               [sets totalCheques, totalAmount]
     *       → return batch
     * </pre>
     *
     * @param zipBytes raw bytes of the uploaded ZIP file
     * @param zipName  original filename for logging
     * @return populated {@link BatchModel}; cheque list empty if XML not found
     * @throws Exception if ZIP is unreadable or XML parsing fails fatally
     */
    @Override
    public BatchModel processZip(byte[] zipBytes, String zipName) throws Exception {
        LOG.info("Processing ZIP: " + zipName + " (" + zipBytes.length + " bytes)");

        // ── Stage 1: Generate batch ID and initialise batch shell ───────────
        String batchId = generateBatchId();
        BatchModel batch = new BatchModel(batchId, "MUM01");
        batch.setStatus(BatchStatus.DRAFT.db());

        // ── Stage 2: Extract all ZIP entries to an in-memory map ────────────
        // LinkedHashMap preserves ZIP entry order (important if XML must come
        // before images in a sequential processing scenario).
        Map<String, byte[]> zipEntryMap = extractAllEntries(zipBytes);
        LOG.info("ZIP contains " + zipEntryMap.size() + " entries: " + zipEntryMap.keySet());

        // ── Stage 3: Locate and parse the embedded XML file ─────────────────
        // Only the FIRST .xml entry is parsed; a ZIP with multiple XMLs will
        // use the first one encountered (log warning if this becomes an issue).
        List<ChequeModel> parsedCheques = new ArrayList<>();
        for (Map.Entry<String, byte[]> zipEntry : zipEntryMap.entrySet()) {
            if (baseName(zipEntry.getKey()).toLowerCase().endsWith(".xml")) {
                LOG.info("Parsing XML: " + zipEntry.getKey());
                parsedCheques = xmlParser.parse(
                    new ByteArrayInputStream(zipEntry.getValue()), batchId
                );
                break; // only parse the first XML found
            }
        }

        if (parsedCheques.isEmpty()) {
            LOG.warning("No cheques parsed from XML in ZIP: " + zipName);
        }

        // ── Stage 4: Build chequeNo → ChequeModel index ─────────────────────
        // O(1) lookup during image association instead of O(n) linear scan per image.
        Map<String, ChequeModel> chequeByNumberIndex = new LinkedHashMap<>();
        for (ChequeModel cheque : parsedCheques) {
            if (cheque.getChequeNo() != null) {
                chequeByNumberIndex.put(cheque.getChequeNo(), cheque);
            }
        }

        // ── Stage 5: Associate front/rear image bytes with each cheque ───────
        for (Map.Entry<String, byte[]> zipEntry : zipEntryMap.entrySet()) {
            String entryFullPath = zipEntry.getKey();
            String entryBaseName = baseName(entryFullPath).toLowerCase();

            // Skip non-image entries (XML, directories, metadata files)
            if (!entryBaseName.endsWith(".jpg") && !entryBaseName.endsWith(".jpeg")
                    && !entryBaseName.endsWith(".png") && !entryBaseName.endsWith(".tiff")) {
                continue;
            }

            // Derive the cheque number from the image filename
            String derivedChequeNo = extractChequeNo(entryBaseName);
            if (derivedChequeNo == null) {
                LOG.warning("Cannot derive cheque number from image — skipping: " + entryFullPath);
                continue;
            }

            // Look up the matching ChequeModel by derived chequeNo
            ChequeModel matchingCheque = chequeByNumberIndex.get(derivedChequeNo);
            if (matchingCheque == null) {
                LOG.warning("No cheque found for image (chequeNo=" + derivedChequeNo
                    + "): " + entryFullPath);
                continue;
            }

            // Determine front or rear from filename convention
            Boolean isFrontFacing = isFrontImage(entryBaseName);
            if (isFrontFacing == null) {
                LOG.warning("Cannot determine front/rear from filename — skipping: " + entryFullPath);
                continue;
            }

            // Attach raw image bytes — stored as BYTEA in cts_cheques
            if (isFrontFacing) {
                matchingCheque.setFrontImageBytes(zipEntry.getValue());
            } else {
                matchingCheque.setRearImageBytes(zipEntry.getValue());
            }
        }

        // ── Stage 6: Assemble batch and recalculate control totals ──────────
        // Persistence is the caller's responsibility — this class returns the
        // populated model only.
        batch.getCheques().addAll(parsedCheques);
        batch.recalculate(); // sets totalCheques and totalAmount from cheque list
        batch.setStatus(BatchStatus.DRAFT.db());

        LOG.info("ZIP processed — Batch: " + batchId
            + " | Cheques: " + parsedCheques.size()
            + " | Total: ₹" + batch.getTotalAmount());

        return batch;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ZIP EXTRACTION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Reads all non-directory entries from the ZIP bytes into a
     * {@code LinkedHashMap<entryName, entryBytes>}.
     *
     * <p>Preserves ZIP entry order via {@code LinkedHashMap}. Directories
     * are silently skipped. Each file entry is fully buffered into a
     * {@code ByteArrayOutputStream} using 8 KB read blocks.
     *
     * <h3>Called by</h3>
     * {@link #processZip(byte[], String)} — Stage 2.
     *
     * @param zipBytes raw ZIP file bytes
     * @return ordered map of entryName → file bytes for all file entries
     * @throws IOException if the ZIP stream is malformed or unreadable
     */
    private Map<String, byte[]> extractAllEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entryMap = new LinkedHashMap<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(
                new ByteArrayInputStream(zipBytes))) {

            ZipEntry currentEntry;
            while ((currentEntry = zipInputStream.getNextEntry()) != null) {
                if (currentEntry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue; // skip directory markers
                }

                // Buffer entire entry content — CTS ZIP entries are typically small
                ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
                byte[] readBlock = new byte[8192]; // 8 KB read block
                int bytesRead;
                while ((bytesRead = zipInputStream.read(readBlock)) != -1) {
                    entryBuffer.write(readBlock, 0, bytesRead);
                }

                entryMap.put(currentEntry.getName(), entryBuffer.toByteArray());
                zipInputStream.closeEntry();
            }
        }
        return entryMap;
    }

    // ══════════════════════════════════════════════════════════════════════
    // IMAGE FILENAME PARSING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Extracts the cheque number from a lower-cased image base filename.
     *
     * <p>Supports four naming conventions used by different CTS scanner vendors:
     * <pre>
     *   "front_{chequeNo}.jpg"  →  chequeNo  (prefix-style, front)
     *   "rear_{chequeNo}.jpg"   →  chequeNo  (prefix-style, rear)
     *   "{chequeNo}_f.jpg"      →  chequeNo  (suffix-style, front)
     *   "{chequeNo}_r.jpg"      →  chequeNo  (suffix-style, rear)
     *   "{chequeNo}.jpg"        →  chequeNo  (numeric only, no side marker)
     * </pre>
     *
     * <h3>Called by</h3>
     * {@link #processZip(byte[], String)} — Stage 5, once per image entry.
     *
     * @param lowerCaseBaseName filename (lowercase, no directory path)
     *                          e.g. "front_123456.jpg"
     * @return extracted cheque number string, or null if no pattern matches
     */
    private String extractChequeNo(String lowerCaseBaseName) {
        // Strip file extension to work with just the name stem
        String nameStem = lowerCaseBaseName;
        int dotPosition = nameStem.lastIndexOf('.');
        if (dotPosition >= 0) {
            nameStem = nameStem.substring(0, dotPosition);
        }

        // Pattern: "front_{chequeNo}" or "rear_{chequeNo}"
        if (nameStem.startsWith("front_") || nameStem.startsWith("rear_")) {
            String afterPrefix = nameStem.substring(nameStem.indexOf('_') + 1);
            if (afterPrefix.matches("\\d+")) {
                return afterPrefix;
            }
        }

        // Pattern: "{chequeNo}_f" or "{chequeNo}_r"
        if (nameStem.matches("\\d+_[fr]")) {
            return nameStem.substring(0, nameStem.lastIndexOf('_'));
        }

        // Pattern: pure numeric stem — treat as front image, no side marker
        if (nameStem.matches("\\d+")) {
            return nameStem;
        }

        return null; // no supported pattern matched
    }

    /**
     * Determines whether an image filename represents the front or rear face
     * of a cheque, based on naming convention.
     *
     * <p>Convention mapping:
     * <pre>
     *   Starts with "front_"  →  true  (front face)
     *   Starts with "rear_"   →  false (rear face)
     *   Contains "_f."        →  true  (front face, suffix style)
     *   Contains "_r."        →  false (rear face, suffix style)
     *   No match              →  null  (caller skips this image)
     * </pre>
     *
     * <h3>Called by</h3>
     * {@link #processZip(byte[], String)} — Stage 5, once per image entry
     * after {@link #extractChequeNo(String)} succeeds.
     *
     * @param lowerCaseBaseName filename (lowercase) e.g. "rear_123456.jpg"
     * @return {@code true} = front face, {@code false} = rear face,
     *         {@code null} = pattern unrecognised (caller should skip)
     */
    private Boolean isFrontImage(String lowerCaseBaseName) {
        if (lowerCaseBaseName.startsWith("front_")) return true;
        if (lowerCaseBaseName.startsWith("rear_"))  return false;
        if (lowerCaseBaseName.contains("_f."))      return true;
        if (lowerCaseBaseName.contains("_r."))      return false;
        return null; // unrecognised convention
    }

    // ══════════════════════════════════════════════════════════════════════
    // UTILITY HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns the base filename from a ZIP entry full path by stripping any
     * leading directory components.
     *
     * <p>Example: {@code "images/front_123456.jpg"} → {@code "front_123456.jpg"}
     *
     * <h3>Called by</h3>
     * {@link #processZip(byte[], String)} — to isolate the filename from the
     * ZIP entry path before pattern matching and extension checks.
     *
     * @param fullEntryPath ZIP entry name as returned by {@link ZipEntry#getName()}
     * @return filename portion only, with no directory separators
     */
    private String baseName(String fullEntryPath) {
        int lastSlashIndex = fullEntryPath.lastIndexOf('/');
        return lastSlashIndex >= 0
            ? fullEntryPath.substring(lastSlashIndex + 1)
            : fullEntryPath;
    }

    /**
     * Generates the next sequential batch ID using the in-memory
     * {@link #BATCH_SEQ} counter.
     *
     * <p>Format: {@code "BATCH" + zero-padded 4-digit sequence}
     * Example: {@code "BATCH0001"}, {@code "BATCH0002"}, …
     *
     * <p><b>Warning:</b> This counter resets on server restart and is not
     * coordinated with the DB sequence used by {@code BatchServiceImpl}.
     * In the two-step scan workflow, the caller replaces this ID with the
     * DB-authoritative one from {@code BatchServiceImpl.createBatch()}.
     *
     * <h3>Called by</h3>
     * {@link #processZip(byte[], String)} — Stage 1.
     *
     * @return next batch ID string (e.g. "BATCH0001")
     */
    private String generateBatchId() {
        return "BATCH" + String.format("%04d", BATCH_SEQ.getAndIncrement());
    }
}