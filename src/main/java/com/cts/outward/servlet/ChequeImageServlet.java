/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeImageServlet.java
 *  Package     : com.cts.outward.servlet
 *  Author      : Umesh M.
 *  Created     : June 2026
 *  Description : Jakarta Servlet that streams cheque front/back
 *                TIFF/JPEG BLOB bytes directly to the browser.
 *                Reads chequeId and side (front|back) from query
 *                params, loads via ChequeDAO, sets correct
 *                Content-Type, and writes bytes to response
 *                output stream. Used by ZUL <image> src URLs.
 * ============================================================
 */

package com.cts.outward.servlet;

import java.io.IOException;
import java.util.logging.Logger;

import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.ChequeEntity;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * ChequeImageServlet — serves front/rear cheque images from Supabase as binary.
 *
 * URL: GET /chequeImage?id=68&side=front GET /chequeImage?id=68&side=rear
 *
 * Registered in web.xml (no @WebServlet annotation — web.xml takes precedence).
 *
 * WHY a servlet and not Base64 in evalJavaScript: ZK evalJavaScript() silently
 * truncates strings > ~64 KB. A front cheque image (109 KB) encodes to ~146 KB
 * Base64 → blank image. Browser fetches <img src="chequeImage?..."> via normal
 * HTTP GET — no limit.
 *
 * Session guard: Requires "loggedUser" attribute in HttpSession.
 * Unauthenticated requests → 401 Unauthorized. This prevents image URLs from
 * being accessed without a valid login.
 */
public class ChequeImageServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(ChequeImageServlet.class.getName());

	private static final String SESS_LOGGED_USER = "loggedUser";

	private final ChequeDAO chequeDAO = new ChequeDAOImpl();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

		// ── 1. Session guard ──────────────────────────────────────────────────
		HttpSession session = req.getSession(false);
		if (session == null || session.getAttribute(SESS_LOGGED_USER) == null) {
			resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Not authenticated");
			return;
		}

		// ── 2. Parse & validate params ────────────────────────────────────────
		String idParam = req.getParameter("id");
		String side = req.getParameter("side"); // "front" or "rear"

		if (idParam == null || idParam.isBlank() || side == null || side.isBlank()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Required params: id (Long), side (front|rear)");
			return;
		}

		long chequeId;
		try {
			chequeId = Long.parseLong(idParam.trim());
		} catch (NumberFormatException e) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid id: " + idParam);
			return;
		}

		if (chequeId <= 0) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "id must be > 0");
			return;
		}

		boolean wantFront = "front".equalsIgnoreCase(side.trim());

		// ── 3. Load image bytes from Supabase via Hibernate ──────────────────
		//
		// loadChequeWithImages() uses session.get() which loads the full entity
		// including the BYTEA columns (front_image, rear_image).
		// The 17-field projection constructor intentionally excludes these.
		//
		byte[] imageBytes;
		try {
			ChequeEntity cheque = chequeDAO.loadChequeWithImages(chequeId);
			if (cheque == null) {
				LOG.warning("ChequeImageServlet: cheque not found id=" + chequeId);
				resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Cheque not found: " + chequeId);
				return;
			}
			imageBytes = wantFront ? cheque.getFrontImage() : cheque.getRearImage();
		} catch (Exception ex) {
			LOG.severe("ChequeImageServlet DB error id=" + chequeId + ": " + ex.getMessage());
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "DB error fetching image");
			return;
		}

		if (imageBytes == null || imageBytes.length == 0) {
			LOG.info("ChequeImageServlet: no " + side + " image for id=" + chequeId);
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No " + side + " image stored for cheque id=" + chequeId);
			return;
		}

		// ── 4. Detect MIME type from magic bytes ──────────────────────────────
		String mime = detectMime(imageBytes);

		// ── 5. Stream to browser ──────────────────────────────────────────────
		resp.setContentType(mime);
		resp.setContentLength(imageBytes.length);
		// Cache 5 min per session — same session re-opens same cheque quickly
		resp.setHeader("Cache-Control", "private, max-age=300");
		resp.setHeader("X-Content-Type-Options", "nosniff");
		resp.getOutputStream().write(imageBytes);
		resp.getOutputStream().flush();

		LOG.fine("ChequeImageServlet: served " + side + " for id=" + chequeId + " (" + imageBytes.length + " bytes, "
				+ mime + ")");
	}

	/**
	 * Reads first 4 magic bytes to determine the image format. Falls back to
	 * image/jpeg for unknown formats.
	 */
	private String detectMime(byte[] b) {
		if (b == null || b.length < 4)
			return "image/jpeg";
		int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
		if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47)
			return "image/png"; // PNG
		if (b0 == 0xFF && b1 == 0xD8)
			return "image/jpeg"; // JPEG/JPG
		if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46)
			return "image/gif"; // GIF
		if ((b0 == 0x49 && b1 == 0x49) || (b0 == 0x4D && b1 == 0x4D))
			return "image/tiff"; // TIFF
		if (b0 == 0x42 && b1 == 0x4D)
			return "image/bmp"; // BMP
		return "image/jpeg";
	}
}
