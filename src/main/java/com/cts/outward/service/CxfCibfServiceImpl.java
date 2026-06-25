package com.cts.outward.service;

import com.cts.outward.dao.CxfCibfDAO;
import com.cts.outward.dao.CxfCibfDAOImpl;
import com.cts.outward.dto.CxfBatchDTO;
import com.cts.outward.dto.CxfChequeDTO;

import com.cts.util.HibernateUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * CXF-CIBF generation service implementation.
 *
 * Pipeline per batch (triggered after VerificationTwo approves — status
 * VER2_DONE):
 *
 * 1. Load cheques from cts_cheques (front_image + rear_image as bytea) 2. Build
 * CXF XML → CxfXmlBuilder → CXF_<batchId>_<ddmmyyyy>.XML 3. Build CIBF XML →
 * CibfXmlBuilder → CIBF_<batchId>_<ddmmyyyy>.xml 4. Write image files from
 * bytea → cheque001_front.png, cheque001_back.png … 5. Pack CXF + CIBF + all
 * images → <batchId>_<ddmmyyyy>.zip 6. UPDATE cts_batches:
 * status=CXF_GENERATED, cxf_file_name, cibf_file_name, generated_at 7. UPDATE
 * cts_batches: status=ACK_PENDING, sent_at (file is ready for NPCI dispatch)
 *
 * BATCH SPLIT RULE (line 52): MAX_CHEQUES_PER_FILE = 10 Batches with > 10
 * cheques are split into multiple CXF+CIBF pairs, all packed into a single ZIP.
 *
 * Output directory: System property navbharat.cxf.outputDir (default
 * C:/cts/generated_files) Presenting bank routing: System property
 * navbharat.cxf.presenterCode (default 560765000) Bank IFSC: System property
 * navbharat.cxf.ifsc (default TEST0000001)
 */
public class CxfCibfServiceImpl implements CxfCibfService {

	private static final Logger LOG = Logger.getLogger(CxfCibfServiceImpl.class.getName());
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("ddMMyyyy");
	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss");
	// NPCI CXF spec file ID counter — zero-padded 10-digit, unique per processing day
	private static final java.util.concurrent.atomic.AtomicInteger FILE_ID_COUNTER =
			new java.util.concurrent.atomic.AtomicInteger(1);

	// ── max cheques per CXF+CIBF file pair ────────
	private static final int MAX_CHEQUES_PER_FILE = 15;
	// ──────────────────────────────────────────────

	private final CxfCibfDAO dao;
	private final CxfXmlBuilder cxfBuilder;
	private final CibfXmlBuilder cibfBuilder;

	/**
	 * Default constructor initializing required DAOs and XML builders.
	 */
	public CxfCibfServiceImpl() {
		this.dao = new CxfCibfDAOImpl();
		this.cxfBuilder = new CxfXmlBuilder();
		this.cibfBuilder = new CibfXmlBuilder();
	}

	// ═══════════════════════════════════════════════════════
	// STAT COUNTS (delegated to DAO)
	// ═══════════════════════════════════════════════════════

	/**
	 * Returns the count of completed batches (CXF-CIBF generation completed).
	 *
	 * @return total count of completed batches
	 */
	@Override
	public long countCompleted() {
		return dao.countCompleted();
	}

	/**
	 * Returns the count of verified batches currently pending CXF-CIBF generation.
	 *
	 * @return total count of pending batches
	 */
	@Override
	public long countPending() {
		return dao.countPending();
	}

	// ═══════════════════════════════════════════════════════
	// GENERATION
	// ═══════════════════════════════════════════════════════

	/**
	 * Generates CXF and CIBF files for all verified batches.
	 *
	 * @return list of generation results per batch
	 */
	@Override
	public List<CxfFileResult> generateAllVerified() {
		List<CxfBatchDTO> batches = dao.findVerifiedBatches();
		List<CxfFileResult> results = new ArrayList<>();
		for (CxfBatchDTO batch : batches) {
			results.add(generateCxfAndCibfFilesForBatch(batch));
		}
		return results;
	}

	/**
	 * Generates a SINGLE combined CXF and CIBF output for ALL the specified batch IDs.
	 * All cheques from all selected batches are pooled together into one generation run,
	 * producing one ZIP (or multiple ZIPs if the cheque count exceeds MAX_CHEQUES_PER_FILE),
	 * and all batch DB records are updated upon success.
	 *
	 * @param batchIds list of batch IDs selected by the user
	 * @return a single CxfFileResult representing the combined generation outcome
	 */
	@Override
	public CxfFileResult generateForBatches(List<String> batchIds) {
		if (batchIds == null || batchIds.isEmpty()) {
			return CxfFileResult.fail("NONE", "No batch IDs provided.");
		}

		// Use the first batch ID as the representative identifier for logging/result
		String primaryBatchId = batchIds.get(0);
		LOG.info("Starting combined CXF/CIBF generation for " + batchIds.size() + " batch(es): " + batchIds);

		try {
			LocalDateTime now = LocalDateTime.now();
			String dateString = now.format(DATE_FORMATTER);
			String timeString = now.format(TIME_FORMATTER);
			String presentingRoutNo = retrievePresentingBankRoutingCode();
			String ifscCode = retrieveBankIfscCode();
			String outputDir = retrieveCxfOutputDirConfig();

			// ── 1. Load and validate all selected batches + pool their cheques ──
			List<CxfBatchDTO> validBatches = new ArrayList<>();
			List<CxfChequeDTO> allCheques = new ArrayList<>();

			for (String batchId : batchIds) {
				CxfBatchDTO batch = dao.findVerifiedBatch(batchId);
				if (batch == null) {
					LOG.warning("Batch not found or not verified, skipping: " + batchId);
					continue;
				}
				List<CxfChequeDTO> cheques = dao.findChequesForBatch(batchId);
				if (cheques.isEmpty()) {
					LOG.warning("No cheques found for batch, skipping: " + batchId);
					continue;
				}
				validBatches.add(batch);
				allCheques.addAll(cheques);
				LOG.info("  Loaded " + cheques.size() + " cheque(s) from batch: " + batchId);
			}

			if (validBatches.isEmpty()) {
				return CxfFileResult.fail(primaryBatchId,
						"None of the selected batches were in a verified state or had cheques.");
			}

			LOG.info("Combined pool: " + allCheques.size() + " cheque(s) across "
					+ validBatches.size() + " batch(es). Limit per file = " + MAX_CHEQUES_PER_FILE);

			// Use the first valid batch as the "primary" representative for XML headers
			CxfBatchDTO primaryBatch = validBatches.get(0);

			// ── 2. Ensure output directory exists ───────────────
			Path outputPath = Paths.get(outputDir);
			Files.createDirectories(outputPath);

			// ── 3. Partition the combined cheque pool ───────────
			String safePrimaryBatchId = primaryBatch.getBatchId().replaceAll("[^A-Za-z0-9_-]", "_");
			List<List<CxfChequeDTO>> parts = partitionChequeListByLimit(allCheques);
			int totalParts = parts.size();
			LOG.info("Combined pool → " + totalParts + " part(s)");

			List<String> zipFileNames = new ArrayList<>();
			List<String> zipFilePaths = new ArrayList<>();
			String firstCxfName = null;
			String firstCibfName = null;

			for (int partIndex = 0; partIndex < totalParts; partIndex++) {
				List<CxfChequeDTO> chunk = parts.get(partIndex);

				// NPCI spec file names — one sequential ID per part
				String fileId         = String.format("%010d", FILE_ID_COUNTER.getAndIncrement());
				String sequenceSuffix = String.format("_%02d", (partIndex + 1));
				String cxfFileName    = "CXF_"  + presentingRoutNo + "_" + dateString + "_" + timeString + "_14_" + fileId + ".XML";
				String cibfFileName   = "CIBF_" + presentingRoutNo + "_" + dateString + "_" + timeString + "_14_" + fileId + sequenceSuffix + ".cibf";

				LOG.info("  Part " + (partIndex + 1) + "/" + totalParts
						+ " — " + chunk.size() + " cheque(s) → " + cxfFileName);

				// ── 4. Build XML content in memory ───────────────
				int offset       = partIndex * MAX_CHEQUES_PER_FILE;
				byte[] cxfBytes  = cxfBuilder.generateCxfXmlString(primaryBatch, chunk, presentingRoutNo, ifscCode, now)
						.getBytes(StandardCharsets.UTF_8);
				byte[] cibfBytes = cibfBuilder.generateCibfXmlString(primaryBatch, chunk, now, offset)
						.getBytes(StandardCharsets.UTF_8);

				if (partIndex == 0) {
					firstCxfName  = cxfFileName;
					firstCibfName = cibfFileName;
				}

				// ── 5. Stream CXF + CIBF directly into ZIP ───────
				String zipSeqSuffix = totalParts > 1 ? sequenceSuffix : "";
				String zipFileName  = "CTS_PACKAGE_" + safePrimaryBatchId + "_COMBINED_"
						+ dateString + "_" + timeString + zipSeqSuffix + ".zip";
				Path zipPath = outputPath.resolve(zipFileName);

				try (ZipOutputStream zipOutputStream = new ZipOutputStream(
						new BufferedOutputStream(new FileOutputStream(zipPath.toFile())))) {

					// CXF entry
					ZipEntry cxfEntry = new ZipEntry(cxfFileName);
					cxfEntry.setSize(cxfBytes.length);
					zipOutputStream.putNextEntry(cxfEntry);
					zipOutputStream.write(cxfBytes);
					zipOutputStream.closeEntry();

					// CIBF entry
					ZipEntry cibfEntry = new ZipEntry(cibfFileName);
					cibfEntry.setSize(cibfBytes.length);
					zipOutputStream.putNextEntry(cibfEntry);
					zipOutputStream.write(cibfBytes);
					zipOutputStream.closeEntry();
				}

				LOG.info("  ZIP saved: " + zipPath.toAbsolutePath());
				zipFileNames.add(zipFileName);
				zipFilePaths.add(zipPath.toAbsolutePath().toString());
			}

			// ── 6. Update DB for ALL valid batches ──────────────
			for (CxfBatchDTO batch : validBatches) {
				dao.markCxfGenerated(batch.getBatchId(), firstCxfName, firstCibfName, now);
			}

			String primaryZipFileName = zipFileNames.isEmpty() ? "" : zipFileNames.get(0);
			String primaryZipFilePath = zipFilePaths.isEmpty() ? "" : zipFilePaths.get(0);
			LOG.info("Combined CXF/CIBF generation SUCCESS: "
					+ validBatches.size() + " batch(es) → " + totalParts + " ZIP(s) in " + outputDir);

			return CxfFileResult.ok(primaryBatchId, firstCxfName, firstCibfName,
					primaryZipFileName, primaryZipFilePath, zipFileNames, zipFilePaths);

		} catch (Exception exception) {
			LOG.log(Level.SEVERE, "Combined CXF/CIBF generation FAILED", exception);
			return CxfFileResult.fail(primaryBatchId, exception.getMessage());
		}
	}

	/**
	 * Generates CXF and CIBF files for a specific batch identifier.
	 * (Kept for single-batch / programmatic use — UI should prefer generateForBatches.)
	 *
	 * @param batchId unique identifier of the batch to process
	 * @return generation result for the batch
	 */
	@Override
	public CxfFileResult generateForBatch(String batchId) {
		CxfBatchDTO batch = dao.findVerifiedBatch(batchId);
		if (batch == null) {
			return CxfFileResult.fail(batchId, "Batch not found or not in a verified state: " + batchId);
		}
		return generateCxfAndCibfFilesForBatch(batch);
	}

	// ═══════════════════════════════════════════════════════
	// PIPELINE
	// ═══════════════════════════════════════════════════════

	/**
	 * Core pipeline method that performs the generation of CXF and CIBF files
	 * for a single verified batch.
	 *
	 * @param batch batch DTO containing details of the batch to generate files for
	 * @return file generation result
	 */
	private CxfFileResult generateCxfAndCibfFilesForBatch(CxfBatchDTO batch) {
		String batchId = batch.getBatchId();
		LOG.info("Starting CXF/CIBF generation for: " + batchId);

		try {
			LocalDateTime now = LocalDateTime.now();
			String dateString = now.format(DATE_FORMATTER);
			String timeString = now.format(TIME_FORMATTER);
			String presentingRoutNo = retrievePresentingBankRoutingCode();
			String ifscCode = retrieveBankIfscCode();
			String outputDir = retrieveCxfOutputDirConfig();

			// ── 1. Load cheques ──────────────────────────────────
			List<CxfChequeDTO> cheques = dao.findChequesForBatch(batchId);
			if (cheques.isEmpty()) {
				return CxfFileResult.fail(batchId, "No cheques found for batch " + batchId);
			}
			LOG.info("Batch " + batchId + " has " + cheques.size() + " cheques. Limit per file = " + MAX_CHEQUES_PER_FILE);

			// ── 2. Ensure generated-files folder exists ──────────
			Path outputPath = Paths.get(outputDir);
			Files.createDirectories(outputPath);

			// ── 3. Partition cheques ─────────────────────────────
			String safeBatch = batchId.replaceAll("[^A-Za-z0-9_-]", "_");
			List<List<CxfChequeDTO>> parts = partitionChequeListByLimit(cheques);
			int totalParts = parts.size();
			LOG.info("Batch " + batchId + " → " + totalParts + " part(s)");

			List<String> zipFileNames = new ArrayList<>();
			List<String> zipFilePaths = new ArrayList<>();
			String firstCxfName = null;
			String firstCibfName = null;

			for (int partIndex = 0; partIndex < totalParts; partIndex++) {
				List<CxfChequeDTO> chunk = parts.get(partIndex);

				String fileId         = String.format("%010d", FILE_ID_COUNTER.getAndIncrement());
				String sequenceSuffix = String.format("_%02d", (partIndex + 1));
				String cxfFileName    = "CXF_"  + presentingRoutNo + "_" + dateString + "_" + timeString + "_14_" + fileId + ".XML";
				String cibfFileName   = "CIBF_" + presentingRoutNo + "_" + dateString + "_" + timeString + "_14_" + fileId + sequenceSuffix + ".cibf";

				LOG.info("  Part " + (partIndex + 1) + "/" + totalParts + " — " + chunk.size() + " cheques → " + cxfFileName);

				int offset       = partIndex * MAX_CHEQUES_PER_FILE;
				byte[] cxfBytes  = cxfBuilder.generateCxfXmlString(batch, chunk, presentingRoutNo, ifscCode, now)
						.getBytes(StandardCharsets.UTF_8);
				byte[] cibfBytes = cibfBuilder.generateCibfXmlString(batch, chunk, now, offset)
						.getBytes(StandardCharsets.UTF_8);

				if (partIndex == 0) {
					firstCxfName  = cxfFileName;
					firstCibfName = cibfFileName;
				}

				String zipSeqSuffix = totalParts > 1 ? sequenceSuffix : "";
				String zipFileName  = "CTS_PACKAGE_" + safeBatch + "_" + dateString + "_" + timeString + zipSeqSuffix + ".zip";
				Path zipPath = outputPath.resolve(zipFileName);

				try (ZipOutputStream zipOutputStream = new ZipOutputStream(
						new BufferedOutputStream(new FileOutputStream(zipPath.toFile())))) {

					ZipEntry cxfEntry = new ZipEntry(cxfFileName);
					cxfEntry.setSize(cxfBytes.length);
					zipOutputStream.putNextEntry(cxfEntry);
					zipOutputStream.write(cxfBytes);
					zipOutputStream.closeEntry();

					ZipEntry cibfEntry = new ZipEntry(cibfFileName);
					cibfEntry.setSize(cibfBytes.length);
					zipOutputStream.putNextEntry(cibfEntry);
					zipOutputStream.write(cibfBytes);
					zipOutputStream.closeEntry();
				}

				LOG.info("  ZIP saved: " + zipPath.toAbsolutePath());
				zipFileNames.add(zipFileName);
				zipFilePaths.add(zipPath.toAbsolutePath().toString());
			}

			dao.markCxfGenerated(batchId, firstCxfName, firstCibfName, now);

			String primaryZipFileName = zipFileNames.isEmpty() ? "" : zipFileNames.get(0);
			String primaryZipFilePath = zipFilePaths.isEmpty() ? "" : zipFilePaths.get(0);
			LOG.info("CXF/CIBF generation SUCCESS: " + batchId + " → " + totalParts + " ZIP(s) in " + outputDir);
			return CxfFileResult.ok(batchId, firstCxfName, firstCibfName, primaryZipFileName, primaryZipFilePath, zipFileNames, zipFilePaths);

		} catch (Exception exception) {
			LOG.log(Level.SEVERE, "CXF/CIBF generation FAILED for: " + batchId, exception);
			return CxfFileResult.fail(batchId, exception.getMessage());
		}
	}

	// ═══════════════════════════════════════════════════════
	// partitionChequeListByLimit()
	// Splits flat list into sublists of MAX_CHEQUES_PER_FILE.
	// Example: 13 cheques → [[0..9], [10..12]]
	// ═══════════════════════════════════════════════════════

	/**
	 * Helper method to partition the flat cheque list into smaller chunks
	 * based on the max cheques per file limit (15 cheques).
	 *
	 * @param allCheques list of all cheques in the batch
	 * @return list of partitioned cheque lists
	 */
	private List<List<CxfChequeDTO>> partitionChequeListByLimit(List<CxfChequeDTO> allCheques) {
		List<List<CxfChequeDTO>> parts = new ArrayList<>();
		int totalChequesCount = allCheques.size();
		for (int chequeIndex = 0; chequeIndex < totalChequesCount; chequeIndex += MAX_CHEQUES_PER_FILE) {
			int end = Math.min(chequeIndex + MAX_CHEQUES_PER_FILE, totalChequesCount);
			parts.add(new ArrayList<>(allCheques.subList(chequeIndex, end)));
		}
		return parts;
	}

	// ═══════════════════════════════════════════════════════
	// CONFIGURATION
	// ═══════════════════════════════════════════════════════

	/**
	 * Resolves the destination directory for ZIP file output.
	 * Priority: system property → hibernate property → default fallback.
	 *
	 * @return absolute directory path string
	 */
	private String retrieveCxfOutputDirConfig() {
		String outputDirProperty = System.getProperty("navbharat.cxf.outputDir");
		if (outputDirProperty != null && !outputDirProperty.isBlank())
			return outputDirProperty;
		try {
			Object hibernateProperty = HibernateUtil.getSessionFactory().getProperties().get("file.cxf.outputDir");
			if (hibernateProperty != null && !hibernateProperty.toString().isBlank())
				return hibernateProperty.toString();
		} catch (Exception ignored) {
		}
		return Paths.get(System.getProperty("user.home"), "cts", "generated_files").toAbsolutePath().toString();
	}

	/**
	 * Sourced presenting bank routing code from system properties or fallback.
	 *
	 * @return bank routing code (typically 9-digits)
	 */
	private String retrievePresentingBankRoutingCode() {
		String presenterCodeProperty = System.getProperty("navbharat.cxf.presenterCode");
		return (presenterCodeProperty != null && !presenterCodeProperty.isBlank()) ? presenterCodeProperty : "560765000";
	}

	/**
	 * Sourced bank IFSC code from system properties or fallback.
	 *
	 * @return bank IFSC code
	 */
	private String retrieveBankIfscCode() {
		String ifscProperty = System.getProperty("navbharat.cxf.ifsc");
		return (ifscProperty != null && !ifscProperty.isBlank()) ? ifscProperty : "TEST0000001";
	}
}