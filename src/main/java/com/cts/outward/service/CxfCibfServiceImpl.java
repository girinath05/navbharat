package com.cts.outward.service;

import com.cts.outward.dao.CxfCibfDAO;
import com.cts.outward.dao.CxfCibfDAOImpl;
import com.cts.outward.dto.CxfBatchDTO;
import com.cts.outward.dto.CxfChequeDTO;

import com.cts.outward.util.HibernateUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
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
 * C:/cts/navbharat/cxf-output) Presenting bank routing: System property
 * navbharat.cxf.presenterCode (default 560765000) Bank IFSC: System property
 * navbharat.cxf.ifsc (default TEST0000001)
 */
public class CxfCibfServiceImpl implements CxfCibfService {

	private static final Logger LOG = Logger.getLogger(CxfCibfServiceImpl.class.getName());
	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("ddMMyyyy");
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss");
	// NPCI CXF spec file ID counter — zero-padded 10-digit, unique per processing day
	private static final java.util.concurrent.atomic.AtomicInteger FILE_ID_CTR =
			new java.util.concurrent.atomic.AtomicInteger(1);

	// ── CHANGE 1: max cheques per CXF+CIBF file pair ────────
	private static final int MAX_CHEQUES_PER_FILE = 15;
	// ────────────────────────────────────────────────────────

	private final CxfCibfDAO dao;
	private final CxfXmlBuilder cxfBuilder;
	private final CibfXmlBuilder cibfBuilder;

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
	 * Generates CXF and CIBF files for a specific batch identifier.
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
	 * for a verified batch. It loads the cheques, splits them into partitions of 15
	 * if needed, creates the XML files, packs them in a ZIP archive, moves it to
	 * the archive, and updates the database state.
	 *
	 * @param batch batch DTO containing details of the batch to generate files for
	 * @return file generation result
	 */
	private CxfFileResult generateCxfAndCibfFilesForBatch(CxfBatchDTO batch) {
		String batchId = batch.getBatchId();
		LOG.info("Starting CXF/CIBF generation for: " + batchId);

		try {
			LocalDateTime now = LocalDateTime.now();
			String dateStr = now.format(DATE_FMT);
			String timeStr = now.format(TIME_FMT);
			String presentingRoutNo = retrievePresentingBankRoutingCode();
			String ifscCode = retrieveBankIfscCode();
			String outputBaseDir = retrieveCxfOutputDirConfig();

			// ── 1. Load cheques with images ──────────────────────
			List<CxfChequeDTO> cheques = dao.findChequesForBatch(batchId);
			if (cheques.isEmpty()) {
				return CxfFileResult.fail(batchId, "No cheques found for batch " + batchId);
			}

			LOG.info("Batch " + batchId + " has " + cheques.size() + " cheques. Limit per file = "
					+ MAX_CHEQUES_PER_FILE);

			// ── 2. Safe batch ID + output directory ──────────────
			String safeBatch = batchId.replaceAll("[^A-Za-z0-9_-]", "_");
			Path batchDir = Paths.get(outputBaseDir, safeBatch);
			Files.createDirectories(batchDir);

			// ── CHANGE 2: split cheques into parts of 15 ─────────
			List<List<CxfChequeDTO>> parts = partitionChequeListByLimit(cheques);
			int totalParts = parts.size();
			LOG.info("Batch " + batchId + " → " + totalParts + " part(s)");

			List<String> zipFileNames = new ArrayList<>();
			List<String> zipFilePaths = new ArrayList<>();
			String firstCxfName = null;
			String firstCibfName = null;

			for (int partIndex = 0; partIndex < totalParts; partIndex++) {
				List<CxfChequeDTO> chunk = parts.get(partIndex);

				// NPCI spec: CXF_nnnnnnnnn_ddmmyyyy_hhmmss_xx_bbbbbbbbbb.XML
				//            CIBF_nnnnnnnnn_ddmmyyyy_hhmmss_xx_bbbbbbbbbb_nn.cibf
				String fileId = String.format("%010d", FILE_ID_CTR.getAndIncrement());
				String seqSuffix = String.format("_%02d", (partIndex + 1));   // _01, _02 … for multi-part
				String cxfFileName  = "CXF_"  + presentingRoutNo + "_" + dateStr + "_" + timeStr
						+ "_14_" + fileId + ".XML";
				String cibfFileName = "CIBF_" + presentingRoutNo + "_" + dateStr + "_" + timeStr
						+ "_14_" + fileId + seqSuffix + ".cibf";

				LOG.info("  Part " + (partIndex + 1) + "/" + totalParts + " — " + chunk.size() + " cheques → " + cxfFileName);

				// Build XML strings for this chunk
				int offset = partIndex * MAX_CHEQUES_PER_FILE;
				String cxfXml = cxfBuilder.generateCxfXmlString(batch, chunk, presentingRoutNo, ifscCode, now);
				String cibfXml = cibfBuilder.generateCibfXmlString(batch, chunk, now, offset);

				// Write CXF + CIBF files
				Path cxfPath = batchDir.resolve(cxfFileName);
				Path cibfPath = batchDir.resolve(cibfFileName);
				Files.write(cxfPath, cxfXml.getBytes(StandardCharsets.UTF_8));
				Files.write(cibfPath, cibfXml.getBytes(StandardCharsets.UTF_8));

				// Write images for this chunk (offset keeps filenames globally unique) - on disk only, not added to ZIP
				List<Path> imagePaths = writeChequeImagesToBatchDirectory(chunk, batchDir, offset);

				if (partIndex == 0) {
					firstCxfName = cxfFileName;
					firstCibfName = cibfFileName;
				}

				// Pack this part into its own ZIP archive (only including CXF and CIBF files)
				String zipSeqSuffix = totalParts > 1 ? seqSuffix : "";
				String zipFileName = "CTS_PACKAGE_" + safeBatch + "_" + dateStr + "_" + timeStr + zipSeqSuffix + ".zip";
				Path zipPath = batchDir.resolve(zipFileName);

				List<Path> filesToZip = new ArrayList<>();
				filesToZip.add(cxfPath);
				filesToZip.add(cibfPath);

				compressFilesIntoZipArchive(zipPath, filesToZip);

				// Copy ZIP to archive folder (reference log)
				copyZipToArchiveDirectory(zipPath, zipFileName);

				// Clean up temporary files so only the ZIP file remains
				try {
					Files.deleteIfExists(cxfPath);
					Files.deleteIfExists(cibfPath);
					for (Path imgPath : imagePaths) {
						Files.deleteIfExists(imgPath);
					}
				} catch (Exception ex) {
					LOG.warning("Failed to clean up temporary files: " + ex.getMessage());
				}

				zipFileNames.add(zipFileName);
				zipFilePaths.add(zipPath.toAbsolutePath().toString());
			}
			// ── END CHANGE 2 ──────────────────────────────────────

			// ── 9. Update DB (two-step) ─────────────────────────────
			// Step 1: CXF_GENERATED  — batch appears in the Completed table with
			//         cxf_file_name, cibf_file_name, and generated_at filled in.
			dao.markCxfGenerated(batchId, firstCxfName, firstCibfName, now);

			String primaryZipFileName = zipFileNames.isEmpty() ? "" : zipFileNames.get(0);
			String primaryZipFilePath = zipFilePaths.isEmpty() ? "" : zipFilePaths.get(0);
			LOG.info("CXF/CIBF generation SUCCESS: " + batchId + " → " + totalParts + " part(s) in " + zipFilePaths);
			return CxfFileResult.ok(batchId, firstCxfName, firstCibfName, primaryZipFileName, primaryZipFilePath, zipFileNames, zipFilePaths);

		} catch (Exception exception) {
			LOG.log(Level.SEVERE, "CXF/CIBF generation FAILED for: " + batchId, exception);
			return CxfFileResult.fail(batchId, exception.getMessage());
		}
	}

	// ═══════════════════════════════════════════════════════
	// CHANGE 3: partitionChequeListByLimit() — new method
	// Splits flat list into sublists of MAX_CHEQUES_PER_FILE.
	// Example: 13 cheques → [[0..9], [10..12]]
	// ═══════════════════════════════════════════════════════

	/**
	 * Helper method to partition the flat cheque list into smaller chunks
	 * based on the max cheques per file limit (15 cheques).
	 *
	 * @param all list of all cheques in the batch
	 * @return list of partitioned cheque lists
	 */
	private List<List<CxfChequeDTO>> partitionChequeListByLimit(List<CxfChequeDTO> all) {
		List<List<CxfChequeDTO>> parts = new ArrayList<>();
		int total = all.size();
		for (int chequeIndex = 0; chequeIndex < total; chequeIndex += MAX_CHEQUES_PER_FILE) {
			int end = Math.min(chequeIndex + MAX_CHEQUES_PER_FILE, total);
			parts.add(new ArrayList<>(all.subList(chequeIndex, end)));
		}
		return parts;
	}

	// ═══════════════════════════════════════════════════════
	// IMAGE FILES (extract bytea → PNG files)
	// CHANGE 4: added offset param so filenames stay globally
	// unique across parts (part1: 001-010, part2: 011-020 …)
	// ═══════════════════════════════════════════════════════

	/**
	 * Writes cheque images (front & rear) from database bytea storage
	 * to files in the batch directory.
	 *
	 * @param cheques  list of cheques to write images for
	 * @param batchDir directory path where images should be saved
	 * @param offset   image numbering offset for multi-part batches
	 * @return list of generated image file paths
	 * @throws IOException in case of write errors
	 */
	private List<Path> writeChequeImagesToBatchDirectory(List<CxfChequeDTO> cheques, Path batchDir, int offset) throws IOException {
		List<Path> paths = new ArrayList<>();
		for (int chequeIndex = 0; chequeIndex < cheques.size(); chequeIndex++) {
			CxfChequeDTO cheque = cheques.get(chequeIndex);
			int seq = offset + chequeIndex + 1; // global 1-based index
			if (cheque.getFrontImage() != null && cheque.getFrontImage().length > 0) {
				Path imagePath = batchDir.resolve(String.format("cheque%03d_front.png", seq));
				Files.write(imagePath, cheque.getFrontImage());
				paths.add(imagePath);
			}
			if (cheque.getRearImage() != null && cheque.getRearImage().length > 0) {
				Path imagePath = batchDir.resolve(String.format("cheque%03d_back.png", seq));
				Files.write(imagePath, cheque.getRearImage());
				paths.add(imagePath);
			}
		}
		return paths;
	}

	// ═══════════════════════════════════════════════════════
	// ZIP — CHANGE 5: accepts List<Path> instead of
	// separate cxf/cibf/images params (supports multi-part)
	// ═══════════════════════════════════════════════════════

	/**
	 * Compresses the list of files into a single ZIP archive.
	 *
	 * @param zipPath destination path of the ZIP archive
	 * @param files   list of file paths to pack inside the archive
	 * @throws IOException in case of compression errors
	 */
	private void compressFilesIntoZipArchive(Path zipPath, List<Path> files) throws IOException {
		try (ZipOutputStream zos = new ZipOutputStream(
				new BufferedOutputStream(new FileOutputStream(zipPath.toFile())))) {
			for (Path file : files) {
				ZipEntry entry = new ZipEntry(file.getFileName().toString());
				entry.setSize(Files.size(file));
				zos.putNextEntry(entry);
				Files.copy(file, zos);
				zos.closeEntry();
			}
		}
	}

	// ═══════════════════════════════════════════════════════
	// ARCHIVE — unchanged from previous version
	// ═══════════════════════════════════════════════════════

	/**
	 * Copies the generated batch ZIP archive to the system archive folder.
	 * If the file already exists, it appends a version suffix to prevent overwrites.
	 *
	 * @param zipPath     path to the generated ZIP archive
	 * @param zipFileName original filename of the ZIP archive
	 */
	private void copyZipToArchiveDirectory(Path zipPath, String zipFileName) {
		try {
			String archiveDirStr = System.getProperty("navbharat.cxf.archiveDir");
			if (archiveDirStr == null || archiveDirStr.isBlank()) {
				archiveDirStr = "C:/cts/navbharat/cxf-zip-archive";
			}
			Path archiveDir = Paths.get(archiveDirStr);
			Files.createDirectories(archiveDir);

			Path dest = archiveDir.resolve(zipFileName);
			if (Files.exists(dest)) {
				String base = zipFileName.replace(".zip", "");
				int version = 2;
				while (Files.exists(archiveDir.resolve(base + "_v" + version + ".zip"))) {
					version++;
				}
				dest = archiveDir.resolve(base + "_v" + version + ".zip");
			}
			Files.copy(zipPath, dest, StandardCopyOption.REPLACE_EXISTING);
			LOG.info("Archive copy saved: " + dest.toAbsolutePath());
		} catch (Exception exception) {
			LOG.warning("Archive copy failed (non-fatal): " + exception.getMessage());
		}
	}

	// ═══════════════════════════════════════════════════════
	// CONFIGURATION — unchanged from previous version
	// ═══════════════════════════════════════════════════════

	/**
	 * Resolves the destination directory path for file generation.
	 * Sourced from system properties or hibernate properties, with a default fallback.
	 *
	 * @return directory output path
	 */
	private String retrieveCxfOutputDirConfig() {
		String outputDirProperty = System.getProperty("navbharat.cxf.outputDir");
		if (outputDirProperty != null && !outputDirProperty.isBlank())
			return outputDirProperty;
		try {
			Object prop = HibernateUtil.getSessionFactory().getProperties().get("file.cxf.outputDir");
			if (prop != null && !prop.toString().isBlank())
				return prop.toString();
		} catch (Exception ignored) {
		}
		return "C:/cts/navbharat/cxf-output";
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