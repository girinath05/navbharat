/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : CtsParser.java
 *  Package     : com.cts.outward.parser
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Base interface / utility class for CTS file
 *                parsing. Defines shared field constants and
 *                helper methods (amount parsing, MICR cleaning)
 *                used by both the XML and ZIP parser
 *                implementations.
 * ============================================================
 */

package com.cts.outward.parser;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import com.cts.outward.entity.BatchEntity;
import com.cts.outward.entity.ChequeEntity;

public interface CtsParser {

	/**
	 * Parse a CTS ZIP byte array into a ParseResult.
	 *
	 * @param zipBytes Raw bytes of the uploaded ZIP file
	 * @param zipName  Original filename (for batch ID generation)
	 * @return ParseResult with BatchEntity + list of ChequeEntity
	 */
	ParseResult parse(byte[] zipBytes, String zipName);

	// ══════════════════════════════════════════════════════════
	// RESULT OBJECT
	// ══════════════════════════════════════════════════════════

	/**
	 * Immutable result of a ZIP parse operation.
	 */
	class ParseResult {

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