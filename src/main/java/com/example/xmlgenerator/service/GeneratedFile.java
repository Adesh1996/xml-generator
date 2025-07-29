
package com.example.xmlgenerator.service;

/**
 * Helper class to hold the generated XML file's name and its content as a byte array.
 */
public class GeneratedFile { // Made public for access from XmlGeneratorApplication
    private final String fileName;
    private final byte[] content;

    public GeneratedFile(String fileName, byte[] content) {
        this.fileName = fileName;
        this.content = content;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getContent() {
        return content;
    }
}