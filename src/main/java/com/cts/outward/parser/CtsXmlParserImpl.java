/*
 * ============================================================
 *  Project     : Navbharat CTS Outward
 *  File        : CtsXmlParserImpl.java
 *  Package     : com.cts.outward.parser
 * ============================================================
 */
package com.cts.outward.parser;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.cts.outward.model.ChequeModel;

public class CtsXmlParserImpl implements CtsXmlParser {

	private static final Logger LOG = Logger.getLogger(CtsXmlParserImpl.class.getName());

	@Override
	public List<ChequeModel> parse(InputStream is, String batchId) {
		List<ChequeModel> cheques = new ArrayList<>();
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);

			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(is);
			doc.getDocumentElement().normalize();

			String root = doc.getDocumentElement().getTagName();
			LOG.info("CTS XML root element: " + root + "  batchId=" + batchId);

			NodeList chequeNodes = doc.getElementsByTagName("Cheque");
			if (chequeNodes.getLength() > 0) {
				cheques.addAll(parseChequeNodes(chequeNodes, batchId));
			} else {
				NodeList instrNodes = doc.getElementsByTagName("Instrument");
				if (instrNodes.getLength() > 0) {
					cheques.addAll(parseInstrumentNodes(instrNodes, batchId));
				} else {
					LOG.warning("No <Cheque> or <Instrument> elements found in XML.");
				}
			}
			LOG.info("Parsed " + cheques.size() + " cheques from XML.");
		} catch (Exception ex) {
			LOG.severe("XML parse error for batchId=" + batchId + ": " + ex.getMessage());
		}
		return cheques;
	}

	// ── <Cheque> schema ───────────────────────────────────────

	private List<ChequeModel> parseChequeNodes(NodeList nodes, String batchId) {
		List<ChequeModel> list = new ArrayList<>();
		for (int i = 0; i < nodes.getLength(); i++) {
			try {
				Element el = (Element) nodes.item(i);
				ChequeModel c = new ChequeModel();
				c.setId(UUID.randomUUID().toString());
				c.setBatchId(batchId);
				c.setChequeNo(text(el, "ChequeNo", "ChequeNumber"));
				c.setSortCode(text(el, "SortCode"));
				c.setAccountNo(text(el, "AccountNo", "AccountNumber"));
				c.setDrawerBank(text(el, "DrawerBank", "DrawerName"));
				c.setChequeDate(text(el, "ChequeDate", "Date"));
				c.setIqaStatus(textOrDefault(el, "IQA", "Pass"));
				c.setVerStatus("Pending");
				c.setStatus("Pending");

				// FIX: TC from MICRLine
				String micrLine = text(el, "MICRLine");
				if (micrLine != null) {
					String[] parts = micrLine.trim().split("\\s+");
					if (parts.length >= 4)
						c.setTransactionCode(parts[parts.length - 1]);
					else if (parts.length == 3)
						c.setTransactionCode(parts[2]);
				}
				if (c.getTransactionCode() == null || c.getTransactionCode().isBlank()) {
					String tc = text(el, "TC", "TransactionCode", "TxCode");
					if (tc != null)
						c.setTransactionCode(tc);
				}

				// FIX: AmountInWords
				String amtWords = text(el, "AmountInWords", "AmtInWords", "AmountWords");
				if (amtWords != null)
					c.setAmountInWords(amtWords);

				// Decompose sort code
				String sc = c.getSortCode();
				if (sc != null && sc.length() == 9) {
					c.setCityCode(sc.substring(0, 3));
					c.setBankCode(sc.substring(3, 6));
					c.setBranchCode(sc.substring(6, 9));
				}

				String amtStr = text(el, "Amount");
				if (amtStr != null && !amtStr.isBlank())
					c.setAmount(new BigDecimal(amtStr.trim()));

				// Mismatch check: parsed words vs computed words
				if (c.getAmount() != null && c.getAmountInWords() != null && !c.getAmountInWords().isBlank()) {
					String expected = com.cts.outward.util.AmountToWords.convert(c.getAmount());
					String expectedNorm = expected == null ? null : expected.replaceAll("(?i)\\bRupees\\s*", "").replaceAll("(?i)\\band\\b\\s*", "").replaceAll("\\s{2,}", " ").trim();
					String wordsNorm = c.getAmountInWords().replaceAll("(?i)\\bRupees\\s*", "").replaceAll("(?i)\\band\\b\\s*", "").replaceAll("\\s{2,}", " ").trim();
					if (!wordsNorm.equalsIgnoreCase(expectedNorm)) {
						c.setAmountWordsMismatch(true);
					}
				}

				String frontFile = text(el, "FrontImage");
				String rearFile = text(el, "RearImage");
				if (frontFile != null)
					c.setFrontImageUrl(frontFile);
				if (rearFile != null)
					c.setRearImageUrl(rearFile);

				c.setDuplicate("Y".equalsIgnoreCase(textOrDefault(el, "Duplicate", "N")));
				c.setHni("Y".equalsIgnoreCase(textOrDefault(el, "HNI", "N")));

				list.add(c);
			} catch (Exception ex) {
				LOG.warning("Skipping malformed <Cheque> element #" + i + ": " + ex.getMessage());
			}
		}
		return list;
	}

	// ── <Instrument> schema ───────────────────────────────────

	private List<ChequeModel> parseInstrumentNodes(NodeList nodes, String batchId) {
		List<ChequeModel> list = new ArrayList<>();
		for (int i = 0; i < nodes.getLength(); i++) {
			try {
				Element el = (Element) nodes.item(i);
				ChequeModel c = new ChequeModel();
				c.setId(UUID.randomUUID().toString());
				c.setBatchId(batchId);
				c.setChequeNo(firstNonNull(text(el, "InstrNo"), text(el, "ChequeNo"), text(el, "SerialNo")));
				c.setAccountNo(firstNonNull(text(el, "AccountNo"), text(el, "AcctNo")));
				c.setSortCode(firstNonNull(text(el, "SortCode"), text(el, "MICRCode")));
				c.setDrawerBank(text(el, "DrawerBank"));
				c.setIqaStatus(textOrDefault(el, "IQA", "Pass"));
				c.setVerStatus("Pending");
				c.setStatus("Pending");

				// FIX: TC from MICRLine
				String micrLine = text(el, "MICRLine");
				if (micrLine != null) {
					String[] parts = micrLine.trim().split("\\s+");
					if (parts.length >= 4)
						c.setTransactionCode(parts[parts.length - 1]);
					else if (parts.length == 3)
						c.setTransactionCode(parts[2]);
				}
				if (c.getTransactionCode() == null || c.getTransactionCode().isBlank()) {
					String tc = text(el, "TC", "TransactionCode", "TxCode");
					if (tc != null)
						c.setTransactionCode(tc);
				}

				// FIX: AmountInWords
				String amtWords = text(el, "AmountInWords", "AmtInWords", "AmountWords");
				if (amtWords != null)
					c.setAmountInWords(amtWords);

				String amtStr = firstNonNull(text(el, "Amount"), text(el, "Amt"));
				if (amtStr != null && !amtStr.isBlank())
					c.setAmount(new BigDecimal(amtStr.trim()));

				// Mismatch check: parsed words vs computed words
				if (c.getAmount() != null && c.getAmountInWords() != null && !c.getAmountInWords().isBlank()) {
					String expected = com.cts.outward.util.AmountToWords.convert(c.getAmount());
					String expectedNorm = expected == null ? null : expected.replaceAll("(?i)\\bRupees\\s*", "").replaceAll("(?i)\\band\\b\\s*", "").replaceAll("\\s{2,}", " ").trim();
					String wordsNorm = c.getAmountInWords().replaceAll("(?i)\\bRupees\\s*", "").replaceAll("(?i)\\band\\b\\s*", "").replaceAll("\\s{2,}", " ").trim();
					if (!wordsNorm.equalsIgnoreCase(expectedNorm)) {
						c.setAmountWordsMismatch(true);
					}
				}

				list.add(c);
			} catch (Exception ex) {
				LOG.warning("Skipping malformed <Instrument> #" + i + ": " + ex.getMessage());
			}
		}
		return list;
	}

	// ── XML helpers ───────────────────────────────────────────

	private String text(Element parent, String... tags) {
		for (String tag : tags) {
			NodeList nl = parent.getElementsByTagName(tag);
			if (nl.getLength() > 0) {
				String v = nl.item(0).getTextContent();
				if (v != null && !v.isBlank())
					return v.trim();
			}
		}
		return null;
	}

	private String textOrDefault(Element parent, String tag, String def) {
		String v = text(parent, tag);
		return v != null ? v : def;
	}

	private String firstNonNull(String... values) {
		for (String v : values)
			if (v != null && !v.isBlank())
				return v;
		return null;
	}
}