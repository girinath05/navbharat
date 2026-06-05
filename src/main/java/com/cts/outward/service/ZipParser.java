/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : CtsZipParser.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Service-layer ZIP parser; orchestrates full
 *                ZIP import pipeline — entry routing, XML parse,
 *                image extraction, batch model assembly — then
 *                hands the result to ZipImportService for
 *                persistence.
 * ============================================================
 */

package com.cts.outward.service;

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

/**
 * ============================================================ ClearPay CTS —
 * CtsZipParser
 *
 * Parses a CTS ZIP bundle and produces a BatchEntity + list of ChequeEntity
 * objects ready to persist via Hibernate.
 *
 * No Firebase. Images stored as byte[] in PostgreSQL (BYTEA).
 *
 * ────────────────────────────────────────────────────────── SUPPORTED ZIP
 * STRUCTURES ──────────────────────────────────────────────────────────
 *
 * Structure A — Folder-per-cheque (your BATCH001.zip format):
 *
 * BATCH001/ ├── CHQ001/ │ ├── cheque.xml │ ├── front.jpg │ └── rear.jpg ├──
 * CHQ002/ │ ├── cheque.xml │ ├── front.jpg │ └── rear.jpg └── ...
 *
 * Structure B — Flat ZIP with one master XML + image files:
 *
 * BATCH0001.zip ├── cheques.xml ├── front_470783.jpg ├── rear_470783.jpg └──
 * ...
 *
 * ────────────────────────────────────────────────────────── XML FORMATS
 * (per-cheque) ──────────────────────────────────────────────────────────
 *
 * Format 1 — your sample (flat fields): <ChequeData>
 * <ChequeId>CHQ001</ChequeId> <ChequeNumber>123456</ChequeNumber>
 * <AccountNumber>200003025040</AccountNumber> <Amount>15000.00</Amount>
 * <ChequeDate>2026-05-20</ChequeDate> <DrawerName>Rajesh Kumar</DrawerName>
 * <PayeeName>Nav Bharat Bank</PayeeName> <FrontImage>front.jpg</FrontImage>
 * <RearImage>rear.jpg</RearImage> </ChequeData>
 *
 * Format 2 — RBI CTS-2010 batch (multi-cheque master XML): <CTSFile> <Batch>
 * <BatchId>BATCH0001</BatchId> <Cheque> <ChequeNo>470783</ChequeNo> ...
 * </Cheque> </Batch> </CTSFile>
 *
 * ============================================================
 */
public class ZipParser {

	private static final Logger LOG = Logger.getLogger(ZipParser.class.getName());

	// ──────────────────────────────────────────────────────────
	// MAIN ENTRY POINT
	// ──────────────────────────────────────────────────────────

	/**
	 * Parse a CTS ZIP byte array into a ParseResult.
	 *
	 * @param zipBytes Raw bytes of the uploaded ZIP file
	 * @param zipName  Original filename (for batch ID generation)
	 * @return ParseResult with BatchEntity + list of ChequeEntity
	 */
	public static ParseResult parse(byte[] zipBytes, String zipName) {

		LOG.info("CtsZipParser.parse() — file: " + zipName + " | size: " + zipBytes.length + " bytes");

		try {
			// Step 1: Extract all entries from ZIP into memory
			Map<String, byte[]> entries = extractZipEntries(zipBytes);

			LOG.info("ZIP entries found: " + entries.keySet());

			// Step 2: Detect structure (folder-per-cheque vs flat)
			boolean isFolderPerCheque = detectFolderPerCheque(entries);

			LOG.info("ZIP structure: "
					+ (isFolderPerCheque ? "FOLDER-PER-CHEQUE (Structure A)" : "FLAT BATCH XML (Structure B)"));

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

	// ══════════════════════════════════════════════════════════
	// STRUCTURE DETECTION
	// ══════════════════════════════════════════════════════════

	/**
	 * Detects if ZIP uses folder-per-cheque layout. Signal: more than one .xml file
	 * present.
	 */
	private static boolean detectFolderPerCheque(Map<String, byte[]> entries) {

		long xmlCount = entries.keySet().stream().filter(k -> k.toLowerCase().endsWith(".xml")).count();

		return xmlCount > 1;
	}

	// ══════════════════════════════════════════════════════════
	// STRUCTURE A — Folder-per-cheque parser
	// ══════════════════════════════════════════════════════════

	private static ParseResult parseFolderPerCheque(Map<String, byte[]> entries, String zipName) throws Exception {

		// Group entries by their parent folder (cheque folder)
		// Key: folder name (e.g. "CHQ001")
		// Value: map of filename -> bytes within that folder
		Map<String, Map<String, byte[]>> folders = groupByFolder(entries);

		LOG.info("Cheque folders detected: " + folders.keySet());

		String batchId = buildBatchId(zipName);
		BatchEntity batch = buildBatchEntity(batchId);

		List<ChequeEntity> cheques = new ArrayList<>();
		BigDecimal batchTotal = BigDecimal.ZERO;

		for (Map.Entry<String, Map<String, byte[]>> folder : folders.entrySet()) {

			String folderName = folder.getKey();
			Map<String, byte[]> folderFiles = folder.getValue();

			// Find the XML file inside this folder
			byte[] xmlBytes = findXmlInFolder(folderFiles);

			if (xmlBytes == null) {
				LOG.warning("No XML found in folder: " + folderName + " — skipping");
				continue;
			}

			// Parse the per-cheque XML
			ChequeEntity cheque = parsePerChequeXml(new ByteArrayInputStream(xmlBytes), batchId, folderName);

			if (cheque == null) {
				LOG.warning("Failed to parse cheque XML in folder: " + folderName);
				continue;
			}

			// Attach front/rear images
			byte[] frontBytes = findImage(folderFiles, true);
			byte[] rearBytes = findImage(folderFiles, false);

			cheque.setFrontImage(frontBytes);
			cheque.setRearImage(rearBytes);

			cheques.add(cheque);

			if (cheque.getAmount() != null) {
				batchTotal = batchTotal.add(cheque.getAmount());
			}

			LOG.info("  Parsed cheque: " + cheque.getChequeNo() + " | Amount: " + cheque.getAmount() + " | Front: "
					+ (frontBytes != null ? frontBytes.length + " bytes" : "MISSING") + " | Rear: "
					+ (rearBytes != null ? rearBytes.length + " bytes" : "MISSING"));
		}

		// Finalise batch totals
		batch.setTotalCheques(cheques.size());
		batch.setTotalAmount(batchTotal);
		batch.setStatus("Submitted");
		batch.setUpdatedAt(LocalDateTime.now());

		LOG.info("ParseResult — Batch: " + batchId + " | Cheques: " + cheques.size() + " | Total: ₹" + batchTotal);

		return new ParseResult(batch, cheques);
	}

	// ══════════════════════════════════════════════════════════
	// STRUCTURE B — Flat batch XML parser
	// ══════════════════════════════════════════════════════════

	private static ParseResult parseFlatBatch(Map<String, byte[]> entries, String zipName) throws Exception {

		String batchId = buildBatchId(zipName);
		BatchEntity batch = buildBatchEntity(batchId);

		// Find the single XML file
		byte[] xmlBytes = null;
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			if (e.getKey().toLowerCase().endsWith(".xml")) {
				xmlBytes = e.getValue();
				LOG.info("Master XML found: " + e.getKey());
				break;
			}
		}

		if (xmlBytes == null) {
			throw new RuntimeException("No XML file found in ZIP");
		}

		// Check if master XML contains a <BatchId> — update batchId if so
		List<ChequeEntity> cheques = parseMasterXml(new ByteArrayInputStream(xmlBytes), batchId, batch);

		// Match images to cheques by cheque number
		for (ChequeEntity cheque : cheques) {
			String no = cheque.getChequeNo();
			if (no == null)
				continue;

			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				String fname = e.getKey().toLowerCase();
				if (!isImage(fname))
					continue;

				String detectedNo = extractChequeNoFromFilename(e.getKey());
				if (!no.equals(detectedNo))
					continue;

				if (isFrontImageFile(fname)) {
					cheque.setFrontImage(e.getValue());
				} else {
					cheque.setRearImage(e.getValue());
				}
			}
		}

		BigDecimal batchTotal = cheques.stream().map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		batch.setTotalCheques(cheques.size());
		batch.setTotalAmount(batchTotal);
		batch.setStatus("Submitted");
		batch.setUpdatedAt(LocalDateTime.now());

		return new ParseResult(batch, cheques);
	}

	// ══════════════════════════════════════════════════════════
	// XML PARSERS
	// ══════════════════════════════════════════════════════════

	/**
	 * Parse a per-cheque XML (one cheque per XML, Structure A). Supports your
	 * sample <ChequeData> format.
	 */
	private static ChequeEntity parsePerChequeXml(InputStream xmlStream, String batchId, String folderName) {

		try {
			Document doc = buildDoc(xmlStream);
			Element root = doc.getDocumentElement();

			ChequeEntity c = new ChequeEntity();
			c.setBatchId(batchId);

			// ChequeData format (your sample)
			c.setChequeNo(firstTag(root, "ChequeNumber", "ChequeNo", "InstrNo"));
			c.setAccountNo(firstTag(root, "AccountNumber", "AccountNo", "AcctNo"));
			c.setDrawerName(firstTag(root, "DrawerName", "Drawer"));
			c.setPayeeName(firstTag(root, "PayeeName", "Payee"));
			c.setChequeDate(firstTag(root, "ChequeDate", "Date"));
			c.setSortCode(firstTag(root, "SortCode", "MICRCode", "MicrCode"));

			// Cheque ID from XML or fallback to folder name
			String chequeId = firstTag(root, "ChequeId", "ChequeID");
			if (chequeId == null)
				chequeId = folderName;
			c.setChequeId(chequeId);

			// Amount
			String amtStr = firstTag(root, "Amount", "Amt");
			if (amtStr != null) {
				try {
					c.setAmount(new BigDecimal(amtStr));
				} catch (NumberFormatException e) {
					LOG.warning("Bad amount in " + folderName + ": " + amtStr);
				}
			}

			// IQA status (default Pass)
			String iqa = firstTag(root, "IQA", "IqaStatus");
			c.setIqaStatus(iqa != null ? iqa : "Pass");
			c.setVerStatus("Pending");
			c.setStatus("Pass".equals(c.getIqaStatus()) ? "Ready" : "MICR_Repair");

			// Cheque number fallback to folder name if not in XML
			if (c.getChequeNo() == null || c.getChequeNo().isBlank()) {
				c.setChequeNo(folderName);
			}

			c.setCreatedAt(LocalDateTime.now());
			c.setUpdatedAt(LocalDateTime.now());

			return c;

		} catch (Exception ex) {
			LOG.severe("parsePerChequeXml error in " + folderName + ": " + ex.getMessage());
			return null;
		}
	}

	/**
	 * Parse a master batch XML (Structure B — one XML, many <Cheque> elements).
	 */
	private static List<ChequeEntity> parseMasterXml(InputStream xmlStream, String batchId, BatchEntity batch)
			throws Exception {

		List<ChequeEntity> list = new ArrayList<>();

		Document doc = buildDoc(xmlStream);
		Element root = doc.getDocumentElement();

		// Try to read BatchId from XML and update batch
		String xmlBatchId = firstTag(root, "BatchId", "BatchID");
		if (xmlBatchId != null && !xmlBatchId.isBlank()) {
			batch.setBatchId(xmlBatchId);
			batchId = xmlBatchId;
		}

		// Try <Cheque> elements first
		NodeList chequeNodes = root.getElementsByTagName("Cheque");

		if (chequeNodes.getLength() == 0) {
			// Try <Instrument> fallback
			chequeNodes = root.getElementsByTagName("Instrument");
		}

		for (int i = 0; i < chequeNodes.getLength(); i++) {
			Element el = (Element) chequeNodes.item(i);
			ChequeEntity c = new ChequeEntity();
			c.setBatchId(batchId);

			c.setChequeNo(firstTag(el, "ChequeNo", "InstrNo", "SerialNo", "ChequeNumber"));
			c.setAccountNo(firstTag(el, "AccountNo", "AccountNumber", "AcctNo"));
			c.setSortCode(firstTag(el, "SortCode", "MICRCode"));
			c.setDrawerName(firstTag(el, "DrawerBank", "DrawerName", "Drawer"));
			c.setPayeeName(firstTag(el, "PayeeName", "Payee"));
			c.setChequeDate(firstTag(el, "ChequeDate", "Date"));

			String amtStr = firstTag(el, "Amount", "Amt");
			if (amtStr != null) {
				try {
					c.setAmount(new BigDecimal(amtStr));
				} catch (NumberFormatException ignored) {
				}
			}

			String iqa = firstTag(el, "IQA");
			c.setIqaStatus(iqa != null ? iqa : "Pass");
			c.setVerStatus("Pending");
			c.setStatus("Pass".equals(c.getIqaStatus()) ? "Ready" : "MICR_Repair");
			c.setCreatedAt(LocalDateTime.now());
			c.setUpdatedAt(LocalDateTime.now());

			list.add(c);
		}

		LOG.info("Master XML parsed: " + list.size() + " cheques");
		return list;
	}

	// ══════════════════════════════════════════════════════════
	// ZIP EXTRACTION HELPERS
	// ══════════════════════════════════════════════════════════

	/**
	 * Extract all non-directory entries from ZIP into a flat map. Key: full
	 * relative path (e.g. "BATCH001/CHQ001/front.jpg") Value: raw bytes
	 */
	private static Map<String, byte[]> extractZipEntries(byte[] zipBytes) throws IOException {

		Map<String, byte[]> map = new LinkedHashMap<>();

		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {

			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {

				if (entry.isDirectory()) {
					zis.closeEntry();
					continue;
				}

				// Normalize path separators
				String name = entry.getName().replace("\\", "/");

				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				byte[] block = new byte[8192];
				int read;
				while ((read = zis.read(block)) != -1) {
					buf.write(block, 0, read);
				}

				map.put(name, buf.toByteArray());
				zis.closeEntry();
			}
		}

		return map;
	}

	/**
	 * Group flat path map by the immediate parent folder of each file.
	 *
	 * Input keys: "BATCH001/CHQ001/cheque.xml", "BATCH001/CHQ001/front.jpg" Output:
	 * "CHQ001" -> { "cheque.xml"->bytes, "front.jpg"->bytes }
	 *
	 * Skips files at the root level (no parent folder).
	 */
	private static Map<String, Map<String, byte[]>> groupByFolder(Map<String, byte[]> entries) {

		// Key: folder name, Value: (filename -> bytes)
		Map<String, Map<String, byte[]>> folders = new LinkedHashMap<>();

		for (Map.Entry<String, byte[]> e : entries.entrySet()) {

			String path = e.getKey();
			String[] parts = path.split("/");

			if (parts.length < 2) {
				// File is at root, not in a cheque folder — skip
				LOG.fine("Root-level file, skipping folder grouping: " + path);
				continue;
			}

			// Second-to-last segment = the cheque folder name
			// e.g. "BATCH001/CHQ001/front.jpg" -> folder="CHQ001", file="front.jpg"
			String folderName = parts[parts.length - 2];
			String fileName = parts[parts.length - 1];

			// Skip the batch root folder itself (e.g. "BATCH001")
			// Only include actual cheque sub-folders
			if (parts.length >= 3) {
				folders.computeIfAbsent(folderName, k -> new LinkedHashMap<>()).put(fileName, e.getValue());
			} else {
				// Two-level: folder/file — folderName is the cheque folder
				folders.computeIfAbsent(folderName, k -> new LinkedHashMap<>()).put(fileName, e.getValue());
			}
		}

		return folders;
	}

	/**
	 * Find the XML file within a cheque folder's file map.
	 */
	private static byte[] findXmlInFolder(Map<String, byte[]> folderFiles) {
		for (Map.Entry<String, byte[]> e : folderFiles.entrySet()) {
			if (e.getKey().toLowerCase().endsWith(".xml")) {
				return e.getValue();
			}
		}
		return null;
	}

	/**
	 * Find front or rear image within a cheque folder. Looks for "front.*" or
	 * "rear.*" filenames.
	 */
	private static byte[] findImage(Map<String, byte[]> folderFiles, boolean front) {
		for (Map.Entry<String, byte[]> e : folderFiles.entrySet()) {
			String name = e.getKey().toLowerCase();
			if (!isImage(name))
				continue;

			if (front && isFrontImageFile(name))
				return e.getValue();
			if (!front && !isFrontImageFile(name))
				return e.getValue();
		}
		return null;
	}

	// ══════════════════════════════════════════════════════════
	// FILENAME HELPERS
	// ══════════════════════════════════════════════════════════

	static boolean isImage(String lowerName) {
		return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png")
				|| lowerName.endsWith(".tiff") || lowerName.endsWith(".tif");
	}

	static boolean isFrontImageFile(String lowerName) {
		if (lowerName.contains("front"))
			return true;
		if (lowerName.contains("rear"))
			return false;
		if (lowerName.matches(".*_f\\..*"))
			return true;
		if (lowerName.matches(".*_r\\..*"))
			return false;
		if (lowerName.startsWith("f"))
			return true;
		if (lowerName.startsWith("r"))
			return false;
		return true; // default to front
	}

	/**
	 * Extract cheque number from flat-zip image filename. front_470783.jpg ->
	 * "470783" rear_470783.jpg -> "470783" 470783_F.jpg -> "470783" F470783.jpg ->
	 * "470783"
	 */
	static String extractChequeNoFromFilename(String fileName) {
		String base = fileName;
		int dot = base.lastIndexOf('.');
		if (dot >= 0)
			base = base.substring(0, dot);

		// front_NNN / rear_NNN
		if (base.toLowerCase().startsWith("front_") || base.toLowerCase().startsWith("rear_")) {
			String num = base.substring(base.indexOf('_') + 1);
			if (num.matches("\\d+"))
				return num;
		}

		// NNN_F / NNN_R
		if (base.matches("\\d+_[FRfr]")) {
			return base.substring(0, base.lastIndexOf('_'));
		}

		// FNNN / RNNN
		if (base.matches("[FRfr]\\d+")) {
			return base.substring(1);
		}

		// bare digits
		if (base.matches("\\d+"))
			return base;

		return null;
	}

	// ══════════════════════════════════════════════════════════
	// XML DOM HELPERS
	// ══════════════════════════════════════════════════════════

	private static Document buildDoc(InputStream is) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		// Disable external entity expansion (XXE protection)
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(is);
		doc.getDocumentElement().normalize();
		return doc;
	}

	/**
	 * Try multiple tag names, return first non-blank value found.
	 */
	private static String firstTag(Element parent, String... tagNames) {
		for (String tag : tagNames) {
			NodeList nl = parent.getElementsByTagName(tag);
			if (nl.getLength() > 0) {
				String v = nl.item(0).getTextContent();
				if (v != null && !v.isBlank())
					return v.trim();
			}
		}
		return null;
	}

	// ══════════════════════════════════════════════════════════
	// BATCH ID GENERATION
	// ══════════════════════════════════════════════════════════

	private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(
			1);

	private static String buildBatchId(String zipName) {
		// If filename is already a batch ID (e.g. BATCH001.zip), use it
		if (zipName != null) {
			String base = zipName.replace(".zip", "").replace(".ZIP", "").trim();
			if (base.toUpperCase().startsWith("BATCH") && base.length() <= 20) {
				return base.toUpperCase();
			}
		}
		return "BATCH" + String.format("%04d", SEQ.getAndIncrement());
	}

	private static BatchEntity buildBatchEntity(String batchId) {
		BatchEntity b = new BatchEntity();
		b.setBatchId(batchId);
		b.setBranchCode("MUM01"); // overridden by composer from session
		b.setStatus("Processing");
		b.setCreatedAt(LocalDateTime.now());
		b.setUpdatedAt(LocalDateTime.now());
		return b;
	}

	// ══════════════════════════════════════════════════════════
	// RESULT OBJECT
	// ══════════════════════════════════════════════════════════

	/**
	 * Immutable result of a ZIP parse operation.
	 */
	public static class ParseResult {

		private final BatchEntity batch;
		private final List<ChequeEntity> cheques;

		public ParseResult(BatchEntity batch, List<ChequeEntity> cheques) {
			this.batch = batch;
			this.cheques = Collections.unmodifiableList(cheques);
		}

		public BatchEntity getBatch() {
			return batch;
		}

		public List<ChequeEntity> getCheques() {
			return cheques;
		}

		public int totalCheques() {
			return cheques.size();
		}

		public BigDecimal totalAmount() {
			return cheques.stream().map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
					.reduce(BigDecimal.ZERO, BigDecimal::add);
		}

		@Override
		public String toString() {
			return "ParseResult{batchId=" + batch.getBatchId() + ", cheques=" + cheques.size() + ", total=₹"
					+ totalAmount() + "}";
		}
	}
}
