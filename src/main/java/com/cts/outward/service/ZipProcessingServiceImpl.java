/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ZipProcessingServiceImpl.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Concrete implementation of ZipProcessingService.
 *                Coordinates CtsZipParserImpl for extraction,
 *                applies high-value threshold checks, calls
 *                ZipImportService for persistence, and wraps
 *                all errors in BatchSubmitException for
 *                composer-level dialog display.
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

import com.cts.outward.model.BatchModel;
import com.cts.outward.model.ChequeModel;
import com.cts.outward.parser.CtsXmlParser;

public class ZipProcessingServiceImpl implements ZipProcessingService {

	private static final Logger LOG = Logger.getLogger(ZipProcessingServiceImpl.class.getName());
	private static final AtomicInteger BATCH_SEQ = new AtomicInteger(1);

	// Firebase gone — only xmlParser needed now
	private final CtsXmlParser xmlParser;

	public ZipProcessingServiceImpl(CtsXmlParser xmlParser) {
		this.xmlParser = xmlParser;
	}

	// ── Main entry point ──────────────────────────────────────

	@Override
	public BatchModel processZip(byte[] zipBytes, String zipName) throws Exception {

		LOG.info("Processing ZIP: " + zipName + " (" + zipBytes.length + " bytes)");

		String batchId = generateBatchId();
		BatchModel batch = new BatchModel(batchId, "MUM01");
		batch.setStatus("Created");

		Map<String, byte[]> entries = extractAllEntries(zipBytes);
		LOG.info("ZIP contains " + entries.size() + " entries: " + entries.keySet());

		// ── Parse XML ─────────────────────────────────────────
		List<ChequeModel> cheques = new ArrayList<>();
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			if (baseName(e.getKey()).toLowerCase().endsWith(".xml")) {
				LOG.info("Parsing XML: " + e.getKey());
				cheques = xmlParser.parse(new ByteArrayInputStream(e.getValue()), batchId);
				break;
			}
		}

		if (cheques.isEmpty()) {
			LOG.warning("No cheques parsed from XML in ZIP: " + zipName);
		}

		// Index cheques by cheque number for O(1) image association
		Map<String, ChequeModel> chequeIndex = new LinkedHashMap<>();
		for (ChequeModel c : cheques) {
			if (c.getChequeNo() != null)
				chequeIndex.put(c.getChequeNo(), c);
		}

		// ── Associate images (bytes only — no cloud upload) ───
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String fullPath = e.getKey();
			String base = baseName(fullPath).toLowerCase();

			if (!base.endsWith(".jpg") && !base.endsWith(".jpeg") && !base.endsWith(".png") && !base.endsWith(".tiff"))
				continue;

			String chequeNo = extractChequeNo(base);
			if (chequeNo == null) {
				LOG.warning("Cannot derive cheque number from image — skipping: " + fullPath);
				continue;
			}

			ChequeModel cheque = chequeIndex.get(chequeNo);
			if (cheque == null) {
				LOG.warning("No cheque for image (chequeNo=" + chequeNo + "): " + fullPath);
				continue;
			}

			Boolean isFront = isFrontImage(base);
			if (isFront == null) {
				LOG.warning("Cannot determine front/rear — skipping: " + fullPath);
				continue;
			}

			// Store raw bytes; ChequeEntity.frontImage / rearImage = BYTEA in DB
			if (isFront) {
				cheque.setFrontImageBytes(e.getValue());
			} else {
				cheque.setRearImageBytes(e.getValue());
			}
		}

		// Persistence handled by caller (BatchChequeEntryComposer → DAO)
		batch.getCheques().addAll(cheques);
		batch.recalculate();
		batch.setStatus("Submitted");

		LOG.info("ZIP processed — Batch: " + batchId + " | Cheques: " + cheques.size() + " | Total: ₹"
				+ batch.getTotalAmount());

		return batch;
	}

	// ── ZIP extraction ────────────────────────────────────────

	private Map<String, byte[]> extractAllEntries(byte[] zipBytes) throws IOException {
		Map<String, byte[]> map = new LinkedHashMap<>();
		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					zis.closeEntry();
					continue;
				}
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				byte[] block = new byte[8192];
				int read;
				while ((read = zis.read(block)) != -1)
					buf.write(block, 0, read);
				map.put(entry.getName(), buf.toByteArray());
				zis.closeEntry();
			}
		}
		return map;
	}

	// ── Cheque-number extraction ──────────────────────────────

	private String extractChequeNo(String lowerBaseName) {
		String base = lowerBaseName;
		int dot = base.lastIndexOf('.');
		if (dot >= 0)
			base = base.substring(0, dot);

		if (base.startsWith("front_") || base.startsWith("rear_")) {
			String after = base.substring(base.indexOf('_') + 1);
			if (after.matches("\\d+"))
				return after;
		}
		if (base.matches("\\d+_[fr]")) {
			return base.substring(0, base.lastIndexOf('_'));
		}
		if (base.matches("\\d+"))
			return base;
		return null;
	}

	private Boolean isFrontImage(String lowerBaseName) {
		if (lowerBaseName.startsWith("front_"))
			return true;
		if (lowerBaseName.startsWith("rear_"))
			return false;
		if (lowerBaseName.contains("_f."))
			return true;
		if (lowerBaseName.contains("_r."))
			return false;
		return null;
	}

	// ── Helpers ───────────────────────────────────────────────

	private String baseName(String fullPath) {
		int slash = fullPath.lastIndexOf('/');
		return slash >= 0 ? fullPath.substring(slash + 1) : fullPath;
	}

	private String getExt(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot >= 0 ? fileName.substring(dot) : ".jpg";
	}

	private String generateBatchId() {
		return "BATCH" + String.format("%04d", BATCH_SEQ.getAndIncrement());
	}
}