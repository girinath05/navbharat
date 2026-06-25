package com.cts.outward.service;

/**
 * Result object returned after processing one batch.
 * Matches the existing CxfFileResult.java in com.cts.outward.service.
 */
public class CxfFileResult {

    /** Indicates whether the file generation was successful. */
    private boolean success;
    /** The ID of the batch associated with this result. */
    private String  batchId;
    /** Name of the generated CXF XML file. */
    private String  cxfFileName;
    /** Name of the generated CIBF XML file. */
    private String  cibfFileName;
    /** Name of the generated ZIP file containing both XML files and cheque images. */
    private String  zipFileName;
    /** Absolute path to the generated ZIP file on the system. */
    private String  zipFilePath;
    /** Error message detail if the generation failed. */
    private String  errorMessage;
    /** List of all ZIP file names generated for partitioned batches. */
    private java.util.List<String> zipFileNames;
    /** List of absolute paths of all generated ZIP files. */
    private java.util.List<String> zipFilePaths;

    /**
     * Static factory method to create a successful generation result for a single ZIP file.
     *
     * @param batchId      the ID of the processed batch
     * @param cxfFileName  the name of the generated CXF file
     * @param cibfFileName the name of the generated CIBF file
     * @param zipFileName  the name of the generated ZIP package
     * @param zipFilePath  the absolute path of the generated ZIP package
     * @return a successful CxfFileResult instance
     */
    public static CxfFileResult ok(String batchId,
                                   String cxfFileName,
                                   String cibfFileName,
                                   String zipFileName,
                                   String zipFilePath) {
        CxfFileResult result = new CxfFileResult();
        result.success      = true;
        result.batchId      = batchId;
        result.cxfFileName  = cxfFileName;
        result.cibfFileName = cibfFileName;
        result.zipFileName  = zipFileName;
        result.zipFilePath  = zipFilePath;
        return result;
    }

    /**
     * Static factory method to create a successful generation result with multiple partition ZIP files.
     *
     * @param batchId      the ID of the processed batch
     * @param cxfFileName  the name of the generated CXF file
     * @param cibfFileName the name of the generated CIBF file
     * @param zipFileName  the name of the primary generated ZIP package
     * @param zipFilePath  the absolute path of the primary generated ZIP package
     * @param zipFileNames the list of all generated ZIP package names
     * @param zipFilePaths the list of absolute paths of all generated ZIP packages
     * @return a successful partition CxfFileResult instance
     */
    public static CxfFileResult ok(String batchId,
                                   String cxfFileName,
                                   String cibfFileName,
                                   String zipFileName,
                                   String zipFilePath,
                                   java.util.List<String> zipFileNames,
                                   java.util.List<String> zipFilePaths) {
        CxfFileResult result = new CxfFileResult();
        result.success      = true;
        result.batchId      = batchId;
        result.cxfFileName  = cxfFileName;
        result.cibfFileName = cibfFileName;
        result.zipFileName  = zipFileName;
        result.zipFilePath  = zipFilePath;
        result.zipFileNames = zipFileNames;
        result.zipFilePaths = zipFilePaths;
        return result;
    }

    /**
     * Static factory method to create a failed generation result.
     *
     * @param batchId the ID of the processed batch
     * @param error   the failure description detail
     * @return a failed CxfFileResult instance
     */
    public static CxfFileResult fail(String batchId, String error) {
        CxfFileResult result = new CxfFileResult();
        result.success      = false;
        result.batchId      = batchId;
        result.errorMessage = error;
        return result;
    }

    /**
     * Checks if the generation was successful.
     *
     * @return true if successful; false otherwise
     */
    public boolean isSuccess() { 
        return success; 
    }

    /**
     * Gets the processed batch ID.
     *
     * @return the batch ID
     */
    public String getBatchId() { 
        return batchId; 
    }

    /**
     * Gets the generated CXF file name.
     *
     * @return the CXF file name
     */
    public String getCxfFileName() { 
        return cxfFileName; 
    }

    /**
     * Gets the generated CIBF file name.
     *
     * @return the CIBF file name
     */
    public String getCibfFileName() { 
        return cibfFileName; 
    }

    /**
     * Gets the generated ZIP file name.
     *
     * @return the ZIP file name
     */
    public String getZipFileName() { 
        return zipFileName; 
    }

    /**
     * Gets the absolute path of the generated ZIP file.
     *
     * @return the ZIP file path
     */
    public String getZipFilePath() { 
        return zipFilePath; 
    }

    /**
     * Gets the error message if generation failed.
     *
     * @return the error message
     */
    public String getErrorMessage() { 
        return errorMessage; 
    }

    /**
     * Gets the list of all generated ZIP file names, fallback parsing the primary file name if empty.
     *
     * @return list of ZIP file names
     */
    public java.util.List<String> getZipFileNames() {
        if (zipFileNames == null) {
            zipFileNames = new java.util.ArrayList<>();
            if (zipFileName != null && !zipFileName.isEmpty()) {
                zipFileNames.add(zipFileName);
            }
        }
        return zipFileNames;
    }

    /**
     * Gets the list of all generated ZIP file paths, fallback parsing the primary path if empty.
     *
     * @return list of ZIP file paths
     */
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