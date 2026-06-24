/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : CtsZipParser.java
 *  Package     : com.cts.outward.parser
 *  Author      : Umesh M.
 *  Date        : 24-06-2026
 *  Description : Marker interface for the ZIP-level CTS parser.
 *                Extended by CtsZipParserImpl, which extracts
 *                XML instrument files and TIFF/JPEG image pairs
 *                from the inbound CTS ZIP bundle.
 * ============================================================
 */
package com.cts.outward.parser;

public interface CtsZipParser {

    /**
     * Parses a CTS ZIP file into a ParseResult.
     * Auto-detects ZIP structure:
     *   Structure A: folder-per-cheque (each cheque has its own XML + images)
     *   Structure B: flat batch XML + all images at root level
     *
     * @param zipBytes raw bytes of the uploaded ZIP from ZK UploadEvent
     * @param zipName  original filename e.g. MUM01_20260610.zip
     * @return ParseResult containing BatchEntity and all parsed ChequeEntity objects
     * @throws RuntimeException wrapping any IO or XML parse failure
     */
    CtsParser.ParseResult parse(byte[] zipBytes, String zipName);

    /**
     * Extracts all file entries from ZIP bytes into an ordered name-to-bytes map.
     * Skips directory entries. Normalises path separators to forward slash.
     * Exposed on the interface to allow isolated testing of the extraction step
     * without triggering full XML parse.
     *
     * @param zipBytes raw ZIP file bytes
     * @return ordered map of entryName to file bytes (directories excluded)
     * @throws java.io.IOException if the ZIP stream is malformed or unreadable
     */
    java.util.Map<String, byte[]> extractZipEntries(byte[] zipBytes) throws java.io.IOException;

}