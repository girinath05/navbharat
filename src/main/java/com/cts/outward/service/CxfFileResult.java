package com.cts.outward.service;

/**
 * Result object returned after processing one batch.
 * Matches the existing CxfFileResult.java in com.cts.outward.service.
 */
public class CxfFileResult {

    private boolean success;
    private String  batchId;
    private String  cxfFileName;
    private String  cibfFileName;
    private String  zipFileName;
    private String  zipFilePath;
    private String  errorMessage;
    private java.util.List<String> zipFileNames;
    private java.util.List<String> zipFilePaths;

    public static CxfFileResult ok(String batchId,
                                   String cxfFileName,
                                   String cibfFileName,
                                   String zipFileName,
                                   String zipFilePath) {
        CxfFileResult r = new CxfFileResult();
        r.success      = true;
        r.batchId      = batchId;
        r.cxfFileName  = cxfFileName;
        r.cibfFileName = cibfFileName;
        r.zipFileName  = zipFileName;
        r.zipFilePath  = zipFilePath;
        return r;
    }

    public static CxfFileResult ok(String batchId,
                                   String cxfFileName,
                                   String cibfFileName,
                                   String zipFileName,
                                   String zipFilePath,
                                   java.util.List<String> zipFileNames,
                                   java.util.List<String> zipFilePaths) {
        CxfFileResult r = new CxfFileResult();
        r.success      = true;
        r.batchId      = batchId;
        r.cxfFileName  = cxfFileName;
        r.cibfFileName = cibfFileName;
        r.zipFileName  = zipFileName;
        r.zipFilePath  = zipFilePath;
        r.zipFileNames = zipFileNames;
        r.zipFilePaths = zipFilePaths;
        return r;
    }

    public static CxfFileResult fail(String batchId, String error) {
        CxfFileResult r = new CxfFileResult();
        r.success      = false;
        r.batchId      = batchId;
        r.errorMessage = error;
        return r;
    }

    public boolean isSuccess()       { return success; }
    public String  getBatchId()      { return batchId; }
    public String  getCxfFileName()  { return cxfFileName; }
    public String  getCibfFileName() { return cibfFileName; }
    public String  getZipFileName()  { return zipFileName; }
    public String  getZipFilePath()  { return zipFilePath; }
    public String  getErrorMessage() { return errorMessage; }

    public java.util.List<String> getZipFileNames() {
        if (zipFileNames == null) {
            zipFileNames = new java.util.ArrayList<>();
            if (zipFileName != null && !zipFileName.isEmpty()) {
                zipFileNames.add(zipFileName);
            }
        }
        return zipFileNames;
    }

    public java.util.List<String> getZipFilePaths() {
        if (zipFilePaths == null) {
            zipFilePaths = new java.util.ArrayList<>();
            if (zipFilePath != null && !zipFilePath.isEmpty()) {
                zipFilePaths.add(zipFilePath);
            }
        }
        return zipFilePaths;
    }
}