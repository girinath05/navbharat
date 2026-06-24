/*
 * ============================================================
 *  Project  : Navbharat CTS Outward
 *  File     : ChequeImageServlet.java
 *  Package  : com.cts.outward.servlet
 *  Purpose  : Serves cheque front/rear images (JPEG) and grayscale
 *             variants (PNG) to the browser via HTTP GET.
 *             Applies session-level caching, background JPEG
 *             recompression, and ETag-based 304 responses.
 *  Author   : [Name]
 *  Date     : June 2026
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

/**
 * File     : ChequeImageServlet.java
 * Package  : com.cts.outward.servlet
 * Purpose  : HTTP servlet that serves cheque scanned images (front, rear,
 *            front-gray, rear-gray) from the database to the browser.
 *            Caches compressed image bytes in the HTTP session to avoid
 *            repeated DB round-trips. Background thread recompresses JPEG
 *            and pre-builds grayscale PNGs so subsequent requests are faster.
 * Author   : [Name]
 * Date     : June 2026
 */
public class ChequeImageServlet extends HttpServlet {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(ChequeImageServlet.class.getName());

    /** Session attribute key for the image byte-cache map. */
    private static final String SESSION_KEY_IMAGE_CACHE = "cts.imgCache";

    /**
     * Maximum entries in the per-session image cache.
     * 20 cheques × 4 sides (front, rear, frontgray, reargray) = 80.
     */
    private static final int CACHE_MAX_ENTRIES = 80;

    /**
     * JPEG compression quality applied before caching (0.0–1.0).
     * 0.65 reduces ~330KB raw cheque scan to ~60-80KB without visible quality loss.
     */
    private static final float JPEG_COMPRESSION_QUALITY = 0.65f;

    /**
     * Background thread pool for JPEG recompression and grayscale pre-computation.
     * Fixed at 2 threads — enough concurrency without over-loading Tomcat.
     */
    private static final ExecutorService BACKGROUND_IMAGE_PROCESSOR = Executors.newFixedThreadPool(2);

    /** DAO for loading cheque entities with image blobs from the database. */
    private final ChequeDAO chequeDAO = new ChequeDAOImpl();

    /**
     * Shuts down the background image-processing thread pool gracefully
     * when the servlet is taken out of service.
     */
    @Override
    public void destroy() {
        BACKGROUND_IMAGE_PROCESSOR.shutdownNow();
        super.destroy();
    }

    /**
     * Handles HTTP GET requests for cheque images.
     *
     * <p>Flow:
     * <ol>
     *   <li>Validate session — reject unauthenticated requests with 401.</li>
     *   <li>Parse and validate {@code id} (chequeId) and {@code side} parameters.</li>
     *   <li>Check session cache — return cached bytes if present (skip DB).</li>
     *   <li>On cache miss: load both image blobs in one DB query, cache raw bytes.</li>
     *   <li>Submit background task: recompress JPEG + pre-build grayscale PNGs.</li>
     *   <li>Apply ETag / If-None-Match → 304 Not Modified if content unchanged.</li>
     *   <li>Write image bytes to the HTTP response with correct MIME type.</li>
     * </ol>
     *
     * @param httpRequest  incoming GET request; must carry {@code id} and {@code side} params
     * @param httpResponse outgoing response; will receive image bytes or an error code
     * @throws ServletException if a servlet-level error occurs
     * @throws IOException      if writing to the response output stream fails
     */
    @Override
    protected void doGet(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
            throws ServletException, IOException {

        // ── 1. Session guard ──────────────────────────────────────────────────────────
        // UAM auth: LoginComposer stores com.cts.uam.model.User under
        // SecurityUtil.SESSION_USER_KEY on both ZK session and HttpSession.
        HttpSession activeSession = httpRequest.getSession(false);
        if (activeSession == null || !(activeSession.getAttribute(
                com.cts.util.SecurityUtil.SESSION_USER_KEY) instanceof com.cts.uam.model.User)) {
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Not authenticated");
            return;
        }

        // ── 2. Parse and validate request parameters ─────────────────────────────────
        String chequeIdParam = httpRequest.getParameter("id");
        String requestedSide = httpRequest.getParameter("side");

        if (chequeIdParam == null || chequeIdParam.isBlank()
                || requestedSide == null || requestedSide.isBlank()) {
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Required: id, side (front|rear|frontgray|reargray)");
            return;
        }

        long chequeId;
        try {
            chequeId = Long.parseLong(chequeIdParam.trim());
        } catch (NumberFormatException numberFormatException) {
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid id: " + chequeIdParam);
            return;
        }
        if (chequeId <= 0) {
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "id must be > 0");
            return;
        }

        // Normalise side value for safe comparison and cache-key construction
        String  normalisedSide       = requestedSide.trim().toLowerCase();
        boolean isFrontRequested     = "front".equals(normalisedSide);
        boolean isRearRequested      = "rear".equals(normalisedSide);
        boolean isFrontGrayRequested = "frontgray".equals(normalisedSide);
        boolean isRearGrayRequested  = "reargray".equals(normalisedSide);

        if (!isFrontRequested && !isRearRequested && !isFrontGrayRequested && !isRearGrayRequested) {
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "side must be front|rear|frontgray|reargray");
            return;
        }

        // ── 3. Session cache lookup ───────────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        Map<String, byte[]> sessionImageCache =
                (Map<String, byte[]>) activeSession.getAttribute(SESSION_KEY_IMAGE_CACHE);
        if (sessionImageCache == null) {
            sessionImageCache = new ConcurrentHashMap<>(CACHE_MAX_ENTRIES);
            activeSession.setAttribute(SESSION_KEY_IMAGE_CACHE, sessionImageCache);
        }

        String cacheKey           = chequeId + ":" + normalisedSide;
        byte[] resolvedImageBytes = sessionImageCache.get(cacheKey);

        if (resolvedImageBytes == null) {

            // ── 4. DB fetch — one query loads both front and rear blobs ──────────────
            String frontCacheKey = chequeId + ":front";
            String rearCacheKey  = chequeId + ":rear";

            byte[] cachedFrontBytes = sessionImageCache.get(frontCacheKey);
            byte[] cachedRearBytes  = sessionImageCache.get(rearCacheKey);

            // freshFetch = true when either blob is missing from cache
            boolean freshFetch = (cachedFrontBytes == null || cachedRearBytes == null);

            if (freshFetch) {
                ChequeEntity chequeEntity;
                try {
                    chequeEntity = chequeDAO.loadChequeWithImages(chequeId);
                } catch (Exception databaseException) {
                    LOGGER.severe("ChequeImageServlet DB error id=" + chequeId
                            + ": " + databaseException.getMessage());
                    httpResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "DB error");
                    return;
                }
                if (chequeEntity == null) {
                    httpResponse.sendError(HttpServletResponse.SC_NOT_FOUND,
                            "Cheque not found: " + chequeId);
                    return;
                }

                /*
                 * Store raw bytes immediately so the first request is served fast.
                 * The background task (step 5) will replace these with compressed bytes,
                 * so subsequent requests for the same cheque are ~5x smaller.
                 */
                byte[] rawFrontImageBytes = chequeEntity.getFrontImage();
                byte[] rawRearImageBytes  = chequeEntity.getRearImage();

                cachedFrontBytes = (rawFrontImageBytes != null && rawFrontImageBytes.length > 0)
                        ? rawFrontImageBytes : null;
                cachedRearBytes  = (rawRearImageBytes  != null && rawRearImageBytes.length  > 0)
                        ? rawRearImageBytes  : null;

                if (cachedFrontBytes != null) cacheIfRoom(sessionImageCache, frontCacheKey, cachedFrontBytes);
                if (cachedRearBytes  != null) cacheIfRoom(sessionImageCache, rearCacheKey,  cachedRearBytes);
            }

            // Resolve the specific side requested by the caller
            if (isFrontRequested) {
                resolvedImageBytes = cachedFrontBytes;
            } else if (isRearRequested) {
                resolvedImageBytes = cachedRearBytes;
            } else if (isFrontGrayRequested) {
                resolvedImageBytes = toGrayscalePng(cachedFrontBytes, chequeId);
                if (resolvedImageBytes != null) cacheIfRoom(sessionImageCache, cacheKey, resolvedImageBytes);
            } else {
                // reargray
                resolvedImageBytes = toGrayscalePng(cachedRearBytes, chequeId);
                if (resolvedImageBytes != null) cacheIfRoom(sessionImageCache, cacheKey, resolvedImageBytes);
            }

            if (resolvedImageBytes == null || resolvedImageBytes.length == 0) {
                httpResponse.sendError(HttpServletResponse.SC_NOT_FOUND,
                        "No image for id=" + chequeId + " side=" + normalisedSide);
                return;
            }

            if (isFrontRequested || isRearRequested) {
                cacheIfRoom(sessionImageCache, cacheKey, resolvedImageBytes);
            }

            // ── 5. Background: recompress JPEG + pre-compute grayscale PNGs ──────────
            /*
             * Recompression (330KB→65KB) is CPU-heavy — offload to background pool.
             * First response uses raw bytes (no wait). Subsequent requests for the same
             * cheque get the compressed bytes already in cache (~5x smaller payload).
             */
            if (freshFetch) {
                final byte[]              snapshotFrontBytes = cachedFrontBytes;
                final byte[]              snapshotRearBytes  = cachedRearBytes;
                final Map<String, byte[]> capturedCache      = sessionImageCache;
                final long                capturedChequeId   = chequeId;

                BACKGROUND_IMAGE_PROCESSOR.submit(() -> {
                    // Recompress front JPEG and replace raw entry in cache
                    if (snapshotFrontBytes != null && snapshotFrontBytes.length > 0) {
                        byte[] compressedFrontBytes = recompressJpeg(snapshotFrontBytes, capturedChequeId, "front");
                        if (compressedFrontBytes != null && compressedFrontBytes.length > 0) {
                            cacheIfRoom(capturedCache, capturedChequeId + ":front", compressedFrontBytes);
                        }
                    }
                    // Recompress rear JPEG and replace raw entry in cache
                    if (snapshotRearBytes != null && snapshotRearBytes.length > 0) {
                        byte[] compressedRearBytes = recompressJpeg(snapshotRearBytes, capturedChequeId, "rear");
                        if (compressedRearBytes != null && compressedRearBytes.length > 0) {
                            cacheIfRoom(capturedCache, capturedChequeId + ":rear", compressedRearBytes);
                        }
                    }
                    // Pre-compute grayscale PNGs from compressed source (smaller → faster ImageIO)
                    byte[] sourceFrontForGray = capturedCache.getOrDefault(
                            capturedChequeId + ":front", snapshotFrontBytes);
                    byte[] sourceRearForGray  = capturedCache.getOrDefault(
                            capturedChequeId + ":rear",  snapshotRearBytes);

                    String frontGrayCacheKey = capturedChequeId + ":frontgray";
                    String rearGrayCacheKey  = capturedChequeId + ":reargray";

                    if (!capturedCache.containsKey(frontGrayCacheKey)
                            && sourceFrontForGray != null && sourceFrontForGray.length > 0) {
                        byte[] frontGrayPngBytes = toGrayscalePng(sourceFrontForGray, capturedChequeId);
                        if (frontGrayPngBytes != null) {
                            cacheIfRoom(capturedCache, frontGrayCacheKey, frontGrayPngBytes);
                        }
                    }
                    if (!capturedCache.containsKey(rearGrayCacheKey)
                            && sourceRearForGray != null && sourceRearForGray.length > 0) {
                        byte[] rearGrayPngBytes = toGrayscalePng(sourceRearForGray, capturedChequeId);
                        if (rearGrayPngBytes != null) {
                            cacheIfRoom(capturedCache, rearGrayCacheKey, rearGrayPngBytes);
                        }
                    }
                });
            }
        }

        // ── 6. ETag / 304 Not Modified — skip response body on cache hit ─────────────
        String computedEtag = "\"" + md5Hex(resolvedImageBytes) + "\"";
        if (computedEtag.equals(httpRequest.getHeader("If-None-Match"))) {
            httpResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        // ── 7. Stream image bytes to browser ─────────────────────────────────────────
        String responseMimeType = (isFrontGrayRequested || isRearGrayRequested) ? "image/png" : "image/jpeg";
        httpResponse.setContentType(responseMimeType);
        httpResponse.setContentLength(resolvedImageBytes.length);
        httpResponse.setHeader("Cache-Control", "private, max-age=3600");
        httpResponse.setHeader("ETag", computedEtag);
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.getOutputStream().write(resolvedImageBytes);
        httpResponse.getOutputStream().flush();

        LOGGER.fine("ChequeImageServlet: served " + normalisedSide + " id=" + chequeId
                + " (" + resolvedImageBytes.length + " B, " + responseMimeType + ")");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Re-compresses raw image bytes as JPEG at {@link #JPEG_COMPRESSION_QUALITY}.
     *
     * <p>Decodes the source bytes using ImageIO (supports JPEG, PNG, BMP, etc.),
     * converts to RGB colour space if needed (JPEG encoder rejects ARGB/GRAY),
     * then encodes as JPEG at the configured quality. Returns the original bytes
     * unchanged if decoding or encoding fails, so the caller always receives a
     * non-null result when the input is non-null.
     *
     * @param sourceImageBytes raw image bytes from the database or cache
     * @param chequeId         cheque ID — used only for log messages
     * @param sideLabel        "front" or "rear" — used only for log messages
     * @return compressed JPEG bytes, or {@code sourceImageBytes} if recompression fails
     */
    private byte[] recompressJpeg(byte[] sourceImageBytes, long chequeId, String sideLabel) {
        try {
            BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(sourceImageBytes));
            if (decodedImage == null) {
                LOGGER.warning("recompressJpeg: decode failed id=" + chequeId
                        + " " + sideLabel + " — serving original");
                return sourceImageBytes;
            }

            // JPEG encoder requires TYPE_INT_RGB; convert if source is ARGB or GRAY
            BufferedImage rgbImage;
            if (decodedImage.getType() == BufferedImage.TYPE_INT_RGB) {
                rgbImage = decodedImage;
            } else {
                rgbImage = new BufferedImage(
                        decodedImage.getWidth(), decodedImage.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgbImage.getGraphics().drawImage(decodedImage, 0, 0, null);
            }

            Iterator<ImageWriter> jpegWriters = ImageIO.getImageWritersByFormatName("jpeg");
            if (!jpegWriters.hasNext()) return sourceImageBytes;
            ImageWriter jpegWriter = jpegWriters.next();

            ImageWriteParam compressionParam = jpegWriter.getDefaultWriteParam();
            compressionParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            compressionParam.setCompressionQuality(JPEG_COMPRESSION_QUALITY);

            // Pre-size the output buffer at 1/4 of source — typical compression ratio
            ByteArrayOutputStream compressedOutputStream =
                    new ByteArrayOutputStream(sourceImageBytes.length / 4);
            jpegWriter.setOutput(new MemoryCacheImageOutputStream(compressedOutputStream));
            jpegWriter.write(null, new IIOImage(rgbImage, null, null), compressionParam);
            jpegWriter.dispose();

            byte[] compressedJpegBytes = compressedOutputStream.toByteArray();
            LOGGER.fine("recompressJpeg id=" + chequeId + " " + sideLabel
                    + ": " + sourceImageBytes.length + "B → " + compressedJpegBytes.length + "B ("
                    + (compressedJpegBytes.length * 100 / sourceImageBytes.length) + "%)");
            return compressedJpegBytes;

        } catch (Exception compressionException) {
            LOGGER.warning("recompressJpeg error id=" + chequeId + " " + sideLabel
                    + ": " + compressionException.getMessage() + " — serving original");
            return sourceImageBytes;
        }
    }

    /**
     * Adds the given key-value pair to the cache only if the cache has not yet
     * reached {@link #CACHE_MAX_ENTRIES}. Prevents unbounded session memory growth.
     *
     * @param imageCache the session-scoped image byte cache
     * @param cacheKey   composite key in format {@code "<chequeId>:<side>"}
     * @param imageBytes compressed or raw image bytes to store
     */
    private void cacheIfRoom(Map<String, byte[]> imageCache, String cacheKey, byte[] imageBytes) {
        if (imageCache.size() < CACHE_MAX_ENTRIES) {
            imageCache.put(cacheKey, imageBytes);
        }
    }

    /**
     * Converts raw image bytes (any ImageIO-supported format) to an 8-bit
     * grayscale PNG.
     *
     * <p>Decodes the source, draws onto a {@code TYPE_BYTE_GRAY} BufferedImage,
     * then encodes as PNG. Returns {@code null} if the source is empty or if
     * decoding fails, so callers must null-check the result.
     *
     * @param sourceImageBytes raw or compressed image bytes (JPEG, PNG, etc.)
     * @param chequeId         cheque ID — used only for log messages
     * @return grayscale PNG bytes, or {@code null} on failure
     */
    private byte[] toGrayscalePng(byte[] sourceImageBytes, long chequeId) {
        if (sourceImageBytes == null || sourceImageBytes.length == 0) return null;
        try {
            BufferedImage colourImage = ImageIO.read(new ByteArrayInputStream(sourceImageBytes));
            if (colourImage == null) {
                LOGGER.warning("toGrayscalePng: decode failed id=" + chequeId);
                return null;
            }
            // Draw colour image onto a GRAY canvas — Java2D performs the colour conversion
            BufferedImage grayscaleImage = new BufferedImage(
                    colourImage.getWidth(), colourImage.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            grayscaleImage.getGraphics().drawImage(colourImage, 0, 0, null);

            ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
            if (!ImageIO.write(grayscaleImage, "png", pngOutputStream)) return null;
            return pngOutputStream.toByteArray();

        } catch (Exception conversionException) {
            LOGGER.severe("toGrayscalePng error id=" + chequeId + ": " + conversionException.getMessage());
            return null;
        }
    }

    /**
     * Computes the MD5 hex digest of the given byte array.
     * Used to generate ETag values for HTTP caching (If-None-Match / 304).
     *
     * <p>Falls back to the byte array length as a string if MD5 is unavailable
     * (should never happen on a standard JVM).
     *
     * @param imageData byte array to hash (typically the image bytes being served)
     * @return 32-character lowercase hex MD5 string, or {@code String.valueOf(imageData.length)}
     */
    private String md5Hex(byte[] imageData) {
        try {
            MessageDigest md5Digest  = MessageDigest.getInstance("MD5");
            byte[]        digestBytes = md5Digest.digest(imageData);
            StringBuilder hexBuilder  = new StringBuilder(32);
            for (byte digestByte : digestBytes) {
                hexBuilder.append(String.format("%02x", digestByte & 0xFF));
            }
            return hexBuilder.toString();
        } catch (Exception digestException) {
            // Extremely unlikely — fall back to length-based pseudo-ETag
            return String.valueOf(imageData.length);
        }
    }
}