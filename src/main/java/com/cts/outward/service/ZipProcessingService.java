/*
 * ============================================================
 *  Project     : NavBharat CTS — Cheque Truncation System
 *  Module      : Outward Clearing — Service Layer
 *  File        : ZipProcessingService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *
 * ──────────────────────────────────────────────────────────────
 *  PURPOSE
 * ──────────────────────────────────────────────────────────────
 *  Service interface for the legacy ZIP-to-BatchModel pipeline.
 *
 *  This interface predates ZipImportService and operates at the
 *  model layer (BatchModel / ChequeModel) rather than the entity
 *  layer (BatchEntity / ChequeEntity). It is retained for the
 *  original parser integration path and as a seam for testing
 *  the XML parsing + image association logic in isolation.
 *
 *  Single implementation: ZipProcessingServiceImpl.java
 *
 * ──────────────────────────────────────────────────────────────
 *  RELATIONSHIP TO ZipImportService
 * ──────────────────────────────────────────────────────────────
 *  ZipImportService (newer) orchestrates the full DB-persist
 *  pipeline using CtsZipParserImpl which works at the entity level.
 *
 *  ZipProcessingService (this, older) stops at the model level —
 *  it returns a BatchModel for the caller to convert and persist.
 *  It is used by BatchChequeEntryComposer's original upload path
 *  and by integration tests that verify parsing behaviour without
 *  touching the DB.
 *
 * ──────────────────────────────────────────────────────────────
 *  PIPELINE OVERVIEW
 * ──────────────────────────────────────────────────────────────
 *  processZip(bytes, name)
 *    1. Generate batch ID (in-memory sequence)
 *    2. Extract all ZIP entries into a name→bytes map
 *    3. Find and parse the embedded XML → List<ChequeModel>
 *    4. Index cheques by chequeNo for O(1) image association
 *    5. Associate front/rear image bytes to each ChequeModel
 *    6. Recalculate batch totals (count, amount)
 *    7. Return BatchModel ready for caller to persist or render
 *
 * ──────────────────────────────────────────────────────────────
 *  ARCHITECTURE — WHERE THIS INTERFACE FITS
 * ──────────────────────────────────────────────────────────────
 *
 *  [Composer]                    [Service]                    [Parser]
 *  ─────────────────────────     ──────────────────────────   ────────────────
 *  BatchChequeEntryComposer ───► ZipProcessingService (this)  CtsXmlParser
 *  (legacy path)                 ZipProcessingServiceImpl ──► CtsXmlParserImpl
 *                                  returns BatchModel
 *                                  (caller converts to entities)
 * ============================================================
 */

package com.cts.outward.service;

import com.cts.outward.model.BatchModel;

/**
 * Service contract for the legacy ZIP-to-BatchModel processing pipeline.
 *
 * <p>Extracts ZIP contents, parses cheque XML data, associates front/rear
 * image bytes, and returns a fully populated {@link BatchModel}. The caller
 * is responsible for converting the model to entities and persisting to DB.
 *
 * <p>For the current DB-persist pipeline, see {@link ZipImportService} which
 * works at the entity level and includes deduplication.
 *
 * @author Umesh M.
 * @see ZipProcessingServiceImpl
 * @see ZipImportService
 */
public interface ZipProcessingService {

    /**
     * Processes a CTS ZIP file from raw bytes to a fully populated
     * {@link BatchModel} with cheque data and image bytes attached.
     *
     * <p>Does NOT persist anything to the DB — the caller is responsible
     * for deduplication and persistence after receiving the model.
     *
     * <h3>Pipeline steps</h3>
     * <ol>
     *   <li>Generate a sequential batch ID (in-memory {@code AtomicInteger}
     *       counter — not DB-driven like {@code BatchServiceImpl.createBatch()})</li>
     *   <li>Extract all ZIP entries into a {@code LinkedHashMap<name, bytes>}
     *       preserving entry order</li>
     *   <li>Locate the first {@code .xml} file in the ZIP and parse it via
     *       {@code CtsXmlParser} → {@code List<ChequeModel>}</li>
     *   <li>Build a chequeNo → ChequeModel index for O(1) image lookup</li>
     *   <li>Iterate image entries ({@code .jpg/.jpeg/.png/.tiff}), derive
     *       chequeNo and front/rear flag from filename, attach bytes to model</li>
     *   <li>Add all ChequeModels to the BatchModel and call
     *       {@code batch.recalculate()} to set totalCheques and totalAmount</li>
     *   <li>Return the populated BatchModel</li>
     * </ol>
     *
     * <h3>Image filename conventions supported</h3>
     * <pre>
     *   front_{chequeNo}.jpg   →  front image for chequeNo
     *   rear_{chequeNo}.jpg    →  rear  image for chequeNo
     *   {chequeNo}_f.jpg       →  front image for chequeNo
     *   {chequeNo}_r.jpg       →  rear  image for chequeNo
     *   {chequeNo}.jpg         →  front image (no side marker)
     * </pre>
     *
     * <h3>Called by</h3>
     * {@code BatchChequeEntryComposer} legacy upload path and integration tests.
     *
     * <h3>Call chain</h3>
     * <pre>
     * BatchChequeEntryComposer (legacy path)
     *   → ZipProcessingServiceImpl.processZip(bytes, name)
     *       → extractAllEntries(bytes)          [unzip all to name→bytes map]
     *       → CtsXmlParser.parse(xmlStream)     [XML → List&lt;ChequeModel&gt;]
     *       → build chequeNo index
     *       → associate image bytes by filename convention
     *       → batch.recalculate()
     *       → return BatchModel
     * </pre>
     *
     * @param zipBytes raw bytes of the uploaded ZIP file
     * @param zipName  original filename for logging (e.g. "MUM01_20260610.zip")
     * @return populated {@link BatchModel} with all cheque data and image bytes;
     *         cheque list may be empty if no valid XML was found in the ZIP
     * @throws Exception if ZIP is malformed, unreadable, or XML parsing fails fatally
     */
    BatchModel processZip(byte[] zipBytes, String zipName) throws Exception;
}