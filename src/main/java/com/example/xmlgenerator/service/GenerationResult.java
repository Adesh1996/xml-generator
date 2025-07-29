
package com.example.xmlgenerator.service;
import java.util.List;
/**
 * Helper class to hold the result of the XML generation, including files and metadata.
 */
public class GenerationResult { // Made public for access from XmlGeneratorApplication
    private final List<GeneratedFile> files;
    private final String fileTypeShortcode;
    private final String batchTransactionType;

    public GenerationResult(List<GeneratedFile> files, String fileTypeShortcode, String batchTransactionType) {
        this.files = files;
        this.fileTypeShortcode = fileTypeShortcode;
        this.batchTransactionType = batchTransactionType;
    }

    public List<GeneratedFile> getFiles() { return files; }
    public String getFileTypeShortcode() { return fileTypeShortcode; }
    public String getBatchTransactionType() { return batchTransactionType; }
}