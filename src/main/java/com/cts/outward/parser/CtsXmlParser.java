/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : CtsXmlParser.java
 *  Package     : com.cts.outward.parser
 *  Author      : Umesh M.
 *  Date        : 24-06-2026
 *  Description : Interface for parsing a single CTS XML
 *                instrument file. Accepts an InputStream and
 *                returns a populated ChequeModel. Implemented
 *                by CtsXmlParserImpl using standard DOM/SAX.
 * ============================================================
 */

package com.cts.outward.parser;

import java.io.InputStream;
import java.util.List;

import com.cts.outward.model.ChequeModel;

/**
 * Contract for parsing CTS XML files into a list of ChequeModel objects. Use
 * {@link CtsXmlParserImpl} as the concrete implementation.
 */
public interface CtsXmlParser {

	/**
	 * Parse a CTS XML input stream and return the cheques it describes.
	 *
	 * @param is      XML input stream (caller is responsible for closing it)
	 * @param batchId batch identifier to stamp on every parsed cheque
	 * @return non-null, possibly empty list of cheques
	 */
	List<ChequeModel> parse(InputStream is, String batchId);
}