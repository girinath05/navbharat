/*
 * ============================================================
 *  Project  : Navbharat CTS Outward
 *  File     : BatchSubmitException.java
 *  Package  : com.cts.outward.exception
 *  Purpose  : Checked exception thrown by the service layer when
 *             batch submission fails validation or persistence.
 *             Carries a user-facing message surfaced by the
 *             composer as a ZK Messagebox alert dialog.
 *  Author   : Umesh M.
 *  Date     : June 2026
 * ============================================================
 */

package com.cts.outward.exception;

/**
 * File    : BatchSubmitException.java
 * Package : com.cts.outward.exception
 * Purpose : Checked exception that represents a business-rule or
 *           persistence failure during batch submission in the CTS
 *           outward processing flow.
 *
 * <p>Thrown by the service layer (e.g. {@code BatchServiceImpl})
 * and caught by the composer layer (e.g. {@code BatchDetailComposer}),
 * which presents the exception message to the user via a ZK
 * {@code Messagebox} alert. The message must therefore be concise
 * and human-readable (not a stack trace or technical detail).
 *
 * <p>Usage pattern in service:
 * <pre>
 *   if (batch.getCheques().isEmpty()) {
 *       throw new BatchSubmitException("Batch has no cheques to submit.");
 *   }
 * </pre>
 *
 * <p>Usage pattern in composer:
 * <pre>
 *   try {
 *       batchService.submitBatch(batchId);
 *   } catch (BatchSubmitException batchSubmitException) {
 *       Messagebox.show(batchSubmitException.getMessage(), "Submit Failed",
 *                       Messagebox.OK, Messagebox.ERROR);
 *   }
 * </pre>
 */
public class BatchSubmitException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new {@code BatchSubmitException} with the given
     * user-facing error message.
     *
     * @param userFacingMessage concise, human-readable description of why
     *                          the batch submission failed; this message is
     *                          displayed directly in the ZK alert dialog
     */
    public BatchSubmitException(String userFacingMessage) {
        super(userFacingMessage);
    }
}