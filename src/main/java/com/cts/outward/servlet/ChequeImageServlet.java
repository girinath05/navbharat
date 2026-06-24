/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : ChequeImageServlet.java
 *  Package     : com.cts.outward.servlet
 *  Updated     : June 2026
 *
 *  Optimizations:
 *    1. One DB round-trip per cheque per session (both blobs fetched together).
 *    2. JPEG re-compression at quality 0.65 before caching:
 *       ~330KB raw → ~60-80KB served → 4-5x faster browser load.
 *       Gray PNG also benefits (smaller source → faster ImageIO + smaller PNG).
 *    3. Background thread pre-computes rear + both gray PNGs while user views front.
 *    4. ETag (MD5) + If-None-Match → 304 (zero work on revisit).
 *    5. Cache-Control: private, max-age=3600.
 * ============================================================
 */

package com.cts.outward.servlet;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.cts.outward.dao.ChequeDAO;
import com.cts.outward.dao.ChequeDAOImpl;
import com.cts.outward.entity.ChequeEntity;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class ChequeImageServlet extends HttpServlet {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(ChequeImageServlet.class.getName());

    private static final String SESS_IMG_CACHE   = "cts.imgCache";
    private static final int    CACHE_MAX_ENTRIES = 80;   // 20 cheques × 4 sides

    /** JPEG quality for served images (0.0–1.0). 0.65 = ~60-80KB from 330KB raw. */
    private static final float  JPEG_QUALITY = 0.65f;

    private static final ExecutorService GRAY_POOL = Executors.newFixedThreadPool(2);

    private final ChequeDAO chequeDAO = new ChequeDAOImpl();

    @Override
    public void destroy() {
        GRAY_POOL.shutdownNow();
        super.destroy();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        // ── 1. Session guard ──────────────────────────────────────────────────
        // MERGE FIX: LoginComposer no longer sets a raw "loggedUser" attribute;
        // it stores a com.cts.uam.model.User under SecurityUtil.SESSION_USER_KEY
        // on both the ZK session and the HttpSession.
        HttpSession session = req.getSession(false);
        if (session == null || !(session.getAttribute(com.cts.util.SecurityUtil.SESSION_USER_KEY)
                instanceof com.cts.uam.model.User)) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Not authenticated");
            return;
        }

        // ── 2. Parse params ───────────────────────────────────────────────────
        String idParam = req.getParameter("id");
        String side    = req.getParameter("side");

        if (idParam == null || idParam.isBlank() || side == null || side.isBlank()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Required: id, side (front|rear|frontgray|reargray)");
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

        String sideNorm       = side.trim().toLowerCase();
        boolean wantFront     = "front".equals(sideNorm);
        boolean wantRear      = "rear".equals(sideNorm);
        boolean wantFrontGray = "frontgray".equals(sideNorm);
        boolean wantRearGray  = "reargray".equals(sideNorm);

        if (!wantFront && !wantRear && !wantFrontGray && !wantRearGray) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "side must be front|rear|frontgray|reargray");
            return;
        }

        // ── 3. Session cache ──────────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        Map<String, byte[]> imgCache = (Map<String, byte[]>) session.getAttribute(SESS_IMG_CACHE);
        if (imgCache == null) {
            imgCache = new ConcurrentHashMap<>(CACHE_MAX_ENTRIES);
            session.setAttribute(SESS_IMG_CACHE, imgCache);
        }

        String cacheKey   = chequeId + ":" + sideNorm;
        byte[] imageBytes = imgCache.get(cacheKey);

        if (imageBytes == null) {

            // ── 4. DB fetch — one query, both blobs ───────────────────────────
            String frontKey = chequeId + ":front";
            String rearKey  = chequeId + ":rear";

            byte[] frontRaw = imgCache.get(frontKey);
            byte[] rearRaw  = imgCache.get(rearKey);
            boolean freshFetch = (frontRaw == null || rearRaw == null);

            if (freshFetch) {
                ChequeEntity cheque;
                try {
                    cheque = chequeDAO.loadChequeWithImages(chequeId);
                } catch (Exception ex) {
                    LOG.severe("ChequeImageServlet DB error id=" + chequeId + ": " + ex.getMessage());
                    resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "DB error");
                    return;
                }
                if (cheque == null) {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Cheque not found: " + chequeId);
                    return;
                }

                // Serve raw bytes to browser IMMEDIATELY (no recompression delay).
                // Background thread compresses and REPLACES cache entry — subsequent
                // requests for same cheque get the smaller compressed bytes.
                byte[] rawFront = cheque.getFrontImage();
                byte[] rawRear  = cheque.getRearImage();
                frontRaw = (rawFront != null && rawFront.length > 0) ? rawFront : null;
                rearRaw  = (rawRear  != null && rawRear.length  > 0) ? rawRear  : null;

                if (frontRaw != null) cacheIfRoom(imgCache, frontKey, frontRaw);
                if (rearRaw  != null) cacheIfRoom(imgCache, rearKey,  rearRaw);
            }

            // Resolve requested bytes
            if (wantFront) {
                imageBytes = frontRaw;
            } else if (wantRear) {
                imageBytes = rearRaw;
            } else if (wantFrontGray) {
                imageBytes = toGrayscalePng(frontRaw, chequeId);
                if (imageBytes != null) cacheIfRoom(imgCache, cacheKey, imageBytes);
            } else {
                imageBytes = toGrayscalePng(rearRaw, chequeId);
                if (imageBytes != null) cacheIfRoom(imgCache, cacheKey, imageBytes);
            }

            if (imageBytes == null || imageBytes.length == 0) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No image for id=" + chequeId + " side=" + sideNorm);
                return;
            }

            if (wantFront || wantRear) cacheIfRoom(imgCache, cacheKey, imageBytes);

            // ── 5. Background: recompress JPEG + pre-compute grays ────────────
            // Recompression (330KB→65KB) is CPU-heavy — run off request thread.
            // First response gets raw bytes (fast). Subsequent requests for same
            // cheque get compressed bytes from cache (~5x smaller, faster transfer).
            if (freshFetch) {
                final byte[] fb = frontRaw;
                final byte[] rb = rearRaw;
                final Map<String, byte[]> cache = imgCache;
                final long cid = chequeId;
                GRAY_POOL.submit(() -> {
                    // Recompress front
                    if (fb != null && fb.length > 0) {
                        byte[] cf = recompressJpeg(fb, cid, "front");
                        if (cf != null && cf.length > 0) cacheIfRoom(cache, cid + ":front", cf);
                    }
                    // Recompress rear
                    if (rb != null && rb.length > 0) {
                        byte[] cr = recompressJpeg(rb, cid, "rear");
                        if (cr != null && cr.length > 0) cacheIfRoom(cache, cid + ":rear", cr);
                    }
                    // Pre-compute grays from recompressed source (smaller → faster ImageIO)
                    byte[] fSrc = (byte[]) cache.getOrDefault(cid + ":front", fb);
                    byte[] rSrc = (byte[]) cache.getOrDefault(cid + ":rear",  rb);
                    String fgKey = cid + ":frontgray";
                    String rgKey = cid + ":reargray";
                    if (!cache.containsKey(fgKey) && fSrc != null && fSrc.length > 0) {
                        byte[] fg = toGrayscalePng(fSrc, cid);
                        if (fg != null) cacheIfRoom(cache, fgKey, fg);
                    }
                    if (!cache.containsKey(rgKey) && rSrc != null && rSrc.length > 0) {
                        byte[] rg = toGrayscalePng(rSrc, cid);
                        if (rg != null) cacheIfRoom(cache, rgKey, rg);
                    }
                });
            }
        }

        // ── 6. ETag / 304 ────────────────────────────────────────────────────
        String etag = "\"" + md5Hex(imageBytes) + "\"";
        if (etag.equals(req.getHeader("If-None-Match"))) {
            resp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        // ── 7. Stream ─────────────────────────────────────────────────────────
        String mime = (wantFrontGray || wantRearGray) ? "image/png" : "image/jpeg";
        resp.setContentType(mime);
        resp.setContentLength(imageBytes.length);
        resp.setHeader("Cache-Control", "private, max-age=3600");
        resp.setHeader("ETag", etag);
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.getOutputStream().write(imageBytes);
        resp.getOutputStream().flush();

        LOG.fine("ChequeImageServlet: served " + sideNorm + " id=" + chequeId
                + " (" + imageBytes.length + " B, " + mime + ")");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Re-compresses image bytes as JPEG at JPEG_QUALITY.
     * Decodes source (any format ImageIO supports), converts to RGB if needed,
     * then encodes as JPEG. Returns original bytes if re-compression fails.
     */
    private byte[] recompressJpeg(byte[] src, long chequeId, String label) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
            if (img == null) {
                LOG.warning("recompressJpeg: decode failed id=" + chequeId + " " + label + " — serving original");
                return src;
            }

            // JPEG encoder requires RGB (not ARGB, not GRAY)
            BufferedImage rgb;
            if (img.getType() == BufferedImage.TYPE_INT_RGB) {
                rgb = img;
            } else {
                rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.getGraphics().drawImage(img, 0, 0, null);
            }

            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) return src;
            ImageWriter writer = writers.next();

            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(src.length / 4);
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new IIOImage(rgb, null, null), param);
            writer.dispose();

            byte[] compressed = baos.toByteArray();
            LOG.fine("recompressJpeg id=" + chequeId + " " + label
                    + ": " + src.length + "B → " + compressed.length + "B ("
                    + (compressed.length * 100 / src.length) + "%)");
            return compressed;

        } catch (Exception e) {
            LOG.warning("recompressJpeg error id=" + chequeId + " " + label + ": " + e.getMessage() + " — serving original");
            return src;
        }
    }

    private void cacheIfRoom(Map<String, byte[]> cache, String key, byte[] value) {
        if (cache.size() < CACHE_MAX_ENTRIES) cache.put(key, value);
    }

    private byte[] toGrayscalePng(byte[] src, long chequeId) {
        if (src == null || src.length == 0) return null;
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(src));
            if (original == null) {
                LOG.warning("toGrayscalePng: decode failed id=" + chequeId);
                return null;
            }
            BufferedImage gray = new BufferedImage(
                    original.getWidth(), original.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            gray.getGraphics().drawImage(original, 0, 0, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(gray, "png", baos)) return null;
            return baos.toByteArray();
        } catch (Exception e) {
            LOG.severe("toGrayscalePng error id=" + chequeId + ": " + e.getMessage());
            return null;
        }
    }

    private String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }
}