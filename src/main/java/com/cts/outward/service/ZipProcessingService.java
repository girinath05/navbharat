/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ZipProcessingService.java
 *  Package     : com.cts.outward.service
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Service interface for end-to-end ZIP processing.
 *                Accepts raw ZIP bytes from the composer, drives
 *                parse → validate → persist pipeline, and returns
 *                a BatchModel ready for UI binding.
 * ============================================================
 */

package com.cts.outward.service;

import com.cts.outward.model.BatchModel;

public interface ZipProcessingService {

	BatchModel processZip(byte[] zipBytes, String zipName) throws Exception;
}