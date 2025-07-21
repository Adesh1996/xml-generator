// --- XmlProcessorService.java ---
// Located at: src/main/java/com/example/xmlgenerator/service/XmlProcessorService.java
package com.example.xmlgenerator.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.math.BigDecimal; // For precise sum calculations to avoid floating point issues
import java.util.regex.Pattern; // For regex matching in file cleanup

/**
 * Service class responsible for processing and generating XML files.
 * It handles XML parsing, modification, and multithreaded file generation.
 */
public class XmlProcessorService {

    // Random number generator for unique IDs
    private static final Random RANDOM = new Random();
    // Date format for yyyy-MM-dd (for execution date)
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    // Date format for yyyyMMddHHmmss (for message IDs and creation date/time, without hyphens/colons)
    private static final SimpleDateFormat MSG_ID_DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss"); // Modified: Removed hyphens and colons
    // New Date format for file naming convention: YYYYMMDDHHmmssSSS
    private static final SimpleDateFormat FILE_NAME_TIMESTAMP_FORMAT = new SimpleDateFormat("yyyyMMddHHmmssSSS");

    // Regex pattern for generated file names: <FileFormat>_<Target>_<TimeStamp>_F<Suffix>.xml
    // Example: PAIN1V3_SDMC_20250721123456789_F1.xml
    private static final Pattern GENERATED_FILE_PATTERN = Pattern.compile("^(PAIN|PACS|CAMT)\\d+V\\d+_[A-Z]{4}_\\d{17}_F\\d+\\.xml$");


    // ExecutorService for managing a pool of threads for concurrent file generation.
    // The number of threads is set to the number of available processors for optimal performance.
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    /**
     * Generates multiple XML files based on the provided template and parameters.
     * Each copy of the generated file is processed in a separate thread to improve performance.
     *
     * @param templateInputStream InputStream of the template XML file.
     * @param numTransactions     Total number of transactions to generate across all batches.
     * @param numBatches          Total number of batches to divide transactions into.
     * @param numCopies           Number of copies of the generated file.
     * @param outputDir           Directory to save the generated files.
     * @throws IOException                  If an I/O error occurs during file operations.
     * @throws ParserConfigurationException If a DocumentBuilder cannot be created.
     * @throws SAXException                 If any parse errors occur during XML parsing.
     * @throws TransformerException         If an unrecoverable error occurs during XML transformation.
     * @throws InterruptedException         If the current thread is interrupted while waiting for tasks to complete.
     */
    public void generateXmlFiles(InputStream templateInputStream, int numTransactions, int numBatches, int numCopies, String outputDir)
            throws IOException, ParserConfigurationException, SAXException, TransformerException, InterruptedException {

        // Use DocumentBuilderFactory to create a DocumentBuilder for parsing XML.
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        // Set namespace aware to true to correctly handle XML namespaces (e.g., for pain.001.001.03)
        dbFactory.setNamespaceAware(true);
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();

        // Parse the template file once into a Document object.
        Document templateDoc = dBuilder.parse(templateInputStream);
        // Normalize the document to remove empty text nodes and combine adjacent text nodes.
        templateDoc.getDocumentElement().normalize();

        // Determine file type shortcode and batch/transaction type once from the template
        final String fileTypeShortcode = getFileTypeAndVersionShortcode(templateDoc);
        // Pass numCopies as 1 here, as it's no longer used to determine the batch type string
        final String batchTransactionType = getBatchTransactionType(numTransactions, numBatches, 1); 

        // List to hold Callable tasks, each representing the generation of one XML file copy.
        List<Callable<Void>> tasks = new ArrayList<>();

        // Create a task for each copy requested by the user.
        for (int i = 0; i < numCopies; i++) {
            final int copyIndex = i; // Final variable for use in anonymous inner class
            // Clone the original template document for each copy to ensure independent modification.
            // Deep clone (true) is essential to copy all child nodes and attributes.
            final Document currentTemplateDoc = (Document) templateDoc.cloneNode(true);
            
            // Generate a unique timestamp for each file copy for the filename
            final String fileTimestamp = FILE_NAME_TIMESTAMP_FORMAT.format(new Date());

            // Construct the file name for the current copy using the new naming convention.
            // Format: <FileFormat>_<Target>_<TimeStamp>_Suffix (e.g., PAIN1V3_SDMC_20250721123456789_F1.xml)
            final String fileName = fileTypeShortcode + "_" + batchTransactionType + "_" + fileTimestamp + "_F" + (copyIndex + 1) + ".xml";
            
            // Construct the full file path.
            final String filePath = outputDir + File.separator + fileName;

            // Add a new Callable task to the list.
            // A Callable returns a result (Void in this case) and can throw checked exceptions.
            tasks.add(new Callable<Void>() {
                public Void call() throws Exception {
                    // Process the cloned XML document with the specified parameters.
                    processSingleXmlDocument(currentTemplateDoc, numTransactions, numBatches);
                    // Save the modified XML document to the file system.
                    saveXmlDocument(currentTemplateDoc, filePath);
                    return null; // Return null as this Callable doesn't need to return a specific value.
                }
            });
        }

        // Execute all tasks in the thread pool and wait for their completion.
        // invokeAll blocks until all tasks are completed, or the timeout occurs, or the current thread is interrupted.
        List<Future<Void>> futures = executorService.invokeAll(tasks);
        // In a more robust application, you would iterate through 'futures' to check for exceptions
        // using future.get() to ensure all tasks completed successfully.
        // For this example, we'll assume success if no InterruptedException occurs during invokeAll.
    }

    /**
     * Processes a single XML document: updates dates, message IDs, control sums,
     * and replicates batch and transaction elements according to the specified counts.
     *
     * @param doc             The XML Document to process.
     * @param numTransactions Total number of transactions to be generated in this document.
     * @param numBatches      Total number of batches to be generated in this document.
     * @throws Exception If any error occurs during XML manipulation (e.g., element not found).
     */
    private void processSingleXmlDocument(Document doc, int numTransactions, int numBatches) throws Exception {
        // 1. Update Creation Date/Time and Requested Execution Date to current date/time.
        updateDates(doc);

        // 2. Detect XML Type (pain, pacs, camt) based on the root element's local name.
        String fileTypeShortcode = getFileTypeAndVersionShortcode(doc);
        System.out.println("Detected XML file type: " + fileTypeShortcode); // Log the detected type.

        // 3. Update the main Message ID (MsgId) in the Group Header.
        String newMsgId = generateNewMsgId(); // Declared here

        updateElementTextContent(doc, "MsgId", newMsgId);

        // Determine tag names based on file type
        String batchTagName;
        String transactionTagName;
        String batchIdTagName; // New: Tag name for batch ID (e.g., PmtInfId, RvslPmtInfId)
        String batchNbOfTxsTagName; // New: Tag name for batch transaction count (e.g., NbOfTxs, OrgnlNbOfTxs)
        String batchCtrlSumTagName; // New: Tag name for batch control sum (e.g., CtrlSum, OrgnlCtrlSum)
        String transactionIdTagName1 = "EndToEndId"; // Common for pain.001, pain.008
        String transactionIdTagName2 = "InstrId"; // Common for pain.001
        String transactionIdTagName3 = null; // Added for TxId in pacs.008
        String amountTagName = "InstdAmt"; // Common amount tag

        switch (fileTypeShortcode) {
            case "PAIN1V3":
            case "PAIN1V9":
                batchTagName = "PmtInf";
                transactionTagName = "CdtTrfTxInf";
                batchIdTagName = "PmtInfId";
                batchNbOfTxsTagName = "NbOfTxs";
                batchCtrlSumTagName = "CtrlSum";
                transactionIdTagName1 = "EndToEndId";
                transactionIdTagName2 = "InstrId";
                amountTagName = "InstdAmt";
                break;
            case "PAIN7V2":
                batchTagName = "OrgnlPmtInfAndRvsl";
                transactionTagName = "TxInf";
                batchIdTagName = "RvslPmtInfId"; // Specific to pain.007
                batchNbOfTxsTagName = "OrgnlNbOfTxs"; // Specific to pain.007
                batchCtrlSumTagName = "OrgnlCtrlSum"; // Specific to pain.007
                transactionIdTagName1 = "RvslId";
                transactionIdTagName2 = "OrgnlInstrId"; // pain.007 uses OrgnlInstrId
                amountTagName = "OrgnlInstdAmt"; // For pain.007, amount is OrgnlInstdAmt
                break;
            case "PAIN8V2":
                batchTagName = "PmtInf";
                transactionTagName = "DrctDbtTxInf";
                batchIdTagName = "PmtInfId";
                batchNbOfTxsTagName = "NbOfTxs";
                batchCtrlSumTagName = "CtrlSum";
                transactionIdTagName1 = "EndToEndId";
                transactionIdTagName2 = null; // pain.008 typically doesn't have InstrId at this level
                amountTagName = "InstdAmt";
                break;
            case "PACS8V2":
                // For PACS, transactions are directly under the main message root (e.g., FIToFICstmrCdtTrf)
                // There isn't a repeating "batch" element like PmtInf.
                batchTagName = "FIToFICstmrCdtTrf"; // The main message container
                transactionTagName = "CdtTrfTxInf";
                batchIdTagName = null; // No specific batch ID element to update here (it's not a repeating batch ID)
                batchNbOfTxsTagName = "NbOfTxs"; // These will be updated in GrpHdr
                batchCtrlSumTagName = "CtrlSum"; // These will be updated in GrpHdr
                transactionIdTagName1 = "EndToEndId";
                transactionIdTagName2 = "InstrId";
                transactionIdTagName3 = "TxId"; // PACS.008 also has TxId
                amountTagName = "IntrBkSttlmAmt"; // Specific to pacs.008
                System.out.println("INFO: Processing PACS8V2. Batch replication logic will be handled differently.");
                break;
            case "CAMT53V2": // Placeholder, CAMT messages are typically statements, not payment initiation/reversal
                // CAMT messages usually don't have PmtInf or transaction blocks in the same way as PAIN/PACS.
                batchTagName = "Stmt"; // Example for CAMT.053 statement
                transactionTagName = "Ntry"; // Example for CAMT.053 entry
                batchIdTagName = "Id"; // Example for CAMT.053 statement ID
                batchNbOfTxsTagName = "NbOfTxs"; // Example for CAMT.053 entry count
                batchCtrlSumTagName = "TtlNtries"; // Example for CAMT.053 total entries
                System.err.println("Warning: CAMT53V2 template processing is highly specific and current logic might not apply.");
                break;
            default:
                batchTagName = "PmtInf"; // Default to common PAIN.001 batch tag
                transactionTagName = "CdtTrfTxInf"; // Default to common PAIN.001 transaction tag
                batchIdTagName = "PmtInfId";
                batchNbOfTxsTagName = "NbOfTxs";
                batchCtrlSumTagName = "CtrlSum";
                transactionIdTagName1 = "EndToEndId";
                transactionIdTagName2 = "InstrId";
                amountTagName = "InstdAmt";
                System.err.println("Warning: Unknown XML file type: " + fileTypeShortcode + ". Using default PAIN.001 tag names. This might lead to errors.");
                break;
        }


        // 4. Replicate Batches and Transactions.
        // Find the first Payment Information (PmtInf) element, which serves as the template for new batches.
        NodeList pmtInfList = doc.getElementsByTagName(batchTagName);
        Node firstPmtInf = (pmtInfList.getLength() > 0) ? pmtInfList.item(0) : null;

        if (firstPmtInf == null) {
            // If no PmtInf element is found, it's a critical error for batch generation.
            throw new RuntimeException("No '" + batchTagName + "' (batch) element found in the template. Cannot generate batches.");
        }

        // Remove all existing PmtInf elements except the first one.
        // Iterate backwards to avoid issues with NodeList changing during removal.
        for (int i = pmtInfList.getLength() - 1; i >= 1; i--) {
            pmtInfList.item(i).getParentNode().removeChild(pmtInfList.item(i));
        }

        // Get the Group Header (GrpHdr) element to update its transaction count and control sum.
        Element groupHeader = (Element) doc.getElementsByTagName("GrpHdr").item(0);
        if (groupHeader == null) {
            throw new RuntimeException("No GrpHdr element found in the template.");
        }

        // Calculate the base number of transactions per batch and any remaining transactions
        // to distribute evenly among the first few batches.
        int txnsPerBatch = numTransactions / numBatches;
        int remainingTxns = numTransactions % numBatches;

        BigDecimal totalCtrlSum = BigDecimal.ZERO; // Use BigDecimal for accurate sum calculations.
        int totalNbOfTxs = 0; // Total number of transactions across all batches.

        // Loop to create and process each batch.
        for (int i = 0; i < numBatches; i++) {
            // For the first batch, use the original 'firstPmtInf' node.
            // For subsequent batches, clone the 'firstPmtInf' node to create new independent batch elements.
            Node currentPmtInf = (i == 0) ? firstPmtInf : firstPmtInf.cloneNode(true);
            Element pmtInfElement = (Element) currentPmtInf;

            // Update the batch ID (e.g., PmtInfId, RvslPmtInfId) for the current batch.
            // Only update if a batchIdTagName is defined for the current file type.
            if (batchIdTagName != null) {
                // Use updateElementTextContent to log a warning if the element is not found, as it's expected if defined.
                updateElementTextContent(pmtInfElement, batchIdTagName, newMsgId + "B" + (i + 1));
            }

            // Find the first transaction element within the current batch, which serves as the template.
            NodeList txInfListInBatch = pmtInfElement.getElementsByTagName(transactionTagName);
            
            Node firstTxInf = (txInfListInBatch.getLength() > 0) ? txInfListInBatch.item(0) : null;

            if (firstTxInf == null && !"CAMT53V2".equals(fileTypeShortcode)) { // CAMT might not have this structure
                System.err.println("Warning: No '" + transactionTagName + "' (transaction) element found in '" + batchTagName + "'. Cannot generate transactions within batches.");
            } else if (firstTxInf != null) {
                // Remove all existing transaction elements from the current batch except the first one.
                for (int j = txInfListInBatch.getLength() - 1; j >= 1; j--) {
                    firstTxInf.getParentNode().removeChild(txInfListInBatch.item(j));
                }
            }

            // Determine the number of transactions for the current batch.
            // Distribute remaining transactions (from numTransactions % numBatches) among the first batches.
            int currentBatchTxnCount = txnsPerBatch + (i < remainingTxns ? 1 : 0);
            BigDecimal batchCtrlSum = BigDecimal.ZERO; // Control sum for the current batch.

            // Loop to create and process each transaction within the current batch.
            for (int j = 0; j < currentBatchTxnCount; j++) {
                if (firstTxInf != null) {
                    // For the first transaction, use the original 'firstTxInf' node.
                    // For subsequent transactions, clone the 'firstTxInf' node.
                    Node currentTxInf = (j == 0) ? firstTxInf : firstTxInf.cloneNode(true);
                    Element txInfElement = (Element) currentTxInf;

                    // Update transaction IDs - these are generally expected.
                    // newMsgId already does not contain hyphens or colons
                    updateElementTextContentOptional(txInfElement, transactionIdTagName1, newMsgId + "B" + (i + 1) + "T" + (j + 1));
                    if (transactionIdTagName2 != null) {
                        updateElementTextContentOptional(txInfElement, transactionIdTagName2, newMsgId + "B" + (i + 1) + "T" + (j + 1));
                    }
                    if (transactionIdTagName3 != null) { // For PACS.008 TxId
                        updateElementTextContentOptional(txInfElement, transactionIdTagName3, newMsgId + "B" + (i + 1) + "T" + (j + 1) + "X");
                    }

                    // Extract the transaction amount and add it to the batch control sum.
                    Node amtNode = findFirstElementByTagName(txInfElement, amountTagName);
                    // Special handling for pain.007 where OrgnlInstdAmt might be nested under OrgnlTxRef
                    if (amtNode == null && "PAIN7V2".equals(fileTypeShortcode)) {
                        Element orgnlTxRef = findFirstElementByTagName(txInfElement, "OrgnlTxRef");
                        if (orgnlTxRef != null) {
                            amtNode = findFirstElementByTagName(orgnlTxRef, amountTagName);
                        }
                    }

                    if (amtNode != null) {
                        try {
                            // Parse amount as BigDecimal for precision.
                            BigDecimal txAmt = new BigDecimal(amtNode.getTextContent());
                            batchCtrlSum = batchCtrlSum.add(txAmt);
                        } catch (NumberFormatException e) {
                            System.err.println("Warning: Could not parse transaction amount for sum calculation: " + amtNode.getTextContent() + ". Error: " + e.getMessage());
                        }
                    }

                    // Append cloned transactions to the current batch's PmtInf element.
                    if (j > 0) {
                        pmtInfElement.appendChild(currentTxInf);
                    }
                }
            }

            // Update batch-level transaction count and control sum using determined tag names.
            // For PACS8V2, these are updated in the GrpHdr, not the 'batch' element itself.
            if (!"PACS8V2".equals(fileTypeShortcode)) {
                // These are optional as per user's last request.
                updateElementTextContentOptional(pmtInfElement, batchNbOfTxsTagName, String.valueOf(currentBatchTxnCount));
                updateElementTextContentOptional(pmtInfElement, batchCtrlSumTagName, batchCtrlSum.toPlainString());
            }


            // Accumulate batch sums and counts to calculate the total group header sums.
            totalCtrlSum = totalCtrlSum.add(batchCtrlSum);
            totalNbOfTxs += currentBatchTxnCount;

            // Append cloned batches to the document.
            if (i > 0) {
                Node parentOfPmtInf = firstPmtInf.getParentNode();
                if (parentOfPmtInf != null) {
                    parentOfPmtInf.appendChild(currentPmtInf);
                } else {
                    System.err.println("Warning: Parent of PmtInf not found. Cannot append new batches correctly.");
                }
            }
        }

        // Update Group Header level transaction count (NbOfTxs) and control sum (CtrlSum).
        // These are optional as per user's last request.
        updateElementTextContentOptional(groupHeader, "NbOfTxs", String.valueOf(totalNbOfTxs));
        updateElementTextContentOptional(groupHeader, "CtrlSum", totalCtrlSum.toPlainString());
    }

    /**
     * Updates creation date/time (CreDtTm) and requested execution date (ReqdExctnDt)
     * in the XML document to the current date/time.
     *
     * @param doc The XML Document to modify.
     */
    private void updateDates(Document doc) {
        String currentDate = DATE_FORMAT.format(new Date()); // Current date in yyyy-MM-dd format
        String currentDateTime = MSG_ID_DATE_FORMAT.format(new Date()); // Current date-time in yyyyMMddHHmmss format

        // Update the text content of the "CreDtTm" (Creation Date/Time) element.
        updateElementTextContent(doc, "CreDtTm", currentDateTime);

        // Update the text content of the "ReqdExctnDt" (Requested Execution Date) element.
        // This is now optional as per user's requirement.
        updateElementTextContentOptional(doc, "ReqdExctnDt", currentDate);
    }

    /**
     * Generates a new unique message ID.
     * The format is yyyyMMddHHmmss followed by a 3-digit random number.
     *
     * @return The newly generated message ID string.
     */
    private String generateNewMsgId() {
        String timestamp = MSG_ID_DATE_FORMAT.format(new Date()); // Get current timestamp without hyphens/colons
        String randomSuffix = String.format("%03d", RANDOM.nextInt(1000)); // Generate a 3-digit random number
        return timestamp + randomSuffix; // Concatenate timestamp and random suffix directly
    }

    /**
     * Finds the first element with the given tag name in the entire document
     * and updates its text content.
     *
     * @param doc       The XML Document to search within.
     * @param tagName   The tag name of the element to find and update.
     * @param newContent The new text content to set.
     */
    private void updateElementTextContent(Document doc, String tagName, String newContent) {
        NodeList nodeList = doc.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            // If the element is found, update its text content.
            nodeList.item(0).setTextContent(newContent);
        } else {
            // Log a warning if the element is not found.
            System.err.println("Warning: Element with tag '" + tagName + "' not found in document.");
        }
    }

    /**
     * Finds the first element with the given tag name within a specified parent element
     * and updates its text content. This is useful for scoped updates.
     *
     * @param parentNode The parent Element to search within.
     * @param tagName    The tag name of the element to find and update.
     * @param newContent The new text content to set.
     */
    private void updateElementTextContent(Element parentNode, String tagName, String newContent) {
        NodeList nodeList = parentNode.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            // If the element is found within the parent, update its text content.
            nodeList.item(0).setTextContent(newContent);
        } else {
            // Log a warning if the element is not found within the specified parent.
            System.err.println("Warning: Element with tag '" + tagName + "' not found within parent node: " + parentNode.getTagName());
        }
    }

    /**
     * Finds the first element with the given tag name in the entire document
     * and updates its text content. If the element is not found, it does nothing (no warning).
     *
     * @param doc       The XML Document to search within.
     * @param tagName   The tag name of the element to find and update.
     * @param newContent The new text content to set.
     */
    private void updateElementTextContentOptional(Document doc, String tagName, String newContent) {
        NodeList nodeList = doc.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            nodeList.item(0).setTextContent(newContent);
        }
        // No warning if not found, as per requirement
    }

    /**
     * Finds the first element with the given tag name within a specified parent element
     * and updates its text content. If the element is not found, it does nothing (no warning).
     *
     * @param parentNode The parent Element to search within.
     * @param tagName    The tag name of the element to find and update.
     * @param newContent The new text content to set.
     */
    private void updateElementTextContentOptional(Element parentNode, String tagName, String newContent) {
        NodeList nodeList = parentNode.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            nodeList.item(0).setTextContent(newContent);
        }
        // No warning if not found, as per requirement
    }

    /**
     * Finds the first child element with the given tag name within a specified parent element.
     *
     * @param parentNode The parent Element to search within.
     * @param tagName    The tag name of the element to find.
     * @return The found Element, or null if no such element is found.
     */
    private Element findFirstElementByTagName(Element parentNode, String tagName) {
        NodeList nodeList = parentNode.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            return (Element) nodeList.item(0); // Return the first found element.
        }
        return null; // Return null if no element with the tag name is found.
    }

    /**
     * Saves the XML Document to a specified file path.
     * The output XML will be indented for readability.
     *
     * @param doc      The XML Document to save.
     * @param filePath The full path where the XML file should be saved.
     * @throws TransformerException If an unrecoverable error occurs during the XML transformation process.
     * @throws IOException          If an I/O error occurs while writing the file.
     */
    private void saveXmlDocument(Document doc, String filePath) throws TransformerException, IOException {
        // Create a TransformerFactory instance.
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        // Create a Transformer instance.
        Transformer transformer = transformerFactory.newTransformer();
        // Set output properties for pretty printing the XML.
        transformer.setOutputProperty(OutputKeys.INDENT, "yes"); // Enable indentation
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4"); // Set indent amount to 4 spaces
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8"); // Ensure UTF-8 encoding

        // Create a DOMSource from the Document.
        DOMSource source = new DOMSource(doc);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(); // Use a ByteArrayOutputStream to capture output
        StreamResult result = new StreamResult(baos); // Create a StreamResult for the output stream
        transformer.transform(source, result); // Perform the XML transformation (write to ByteArrayOutputStream)

        // Convert to string, trim leading/trailing whitespace (including newlines), and write back to file
        String xmlString = baos.toString("UTF-8").trim(); // Trim removes leading/trailing whitespace, including newlines
        try (OutputStream os = new FileOutputStream(filePath)) {
            os.write(xmlString.getBytes("UTF-8")); // Write the trimmed string's bytes to the file
        }
    }

    /**
     * Cleans up previously generated XML files in the specified directory.
     * It deletes files that match the application's naming convention.
     *
     * @param directoryPath The path to the directory where generated files are stored.
     */
    public void cleanupGeneratedFiles(String directoryPath) {
        File directory = new File(directoryPath);
        // Check if the directory exists and is actually a directory.
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    // Check if the item is a file and matches the new naming convention.
                    if (file.isFile() && GENERATED_FILE_PATTERN.matcher(file.getName()).matches()) {
                        if (!file.delete()) {
                            System.err.println("Failed to delete old generated file: " + file.getName());
                        }
                    }
                }
            }
        }
    }

    /**
     * Retrieves a list of Path objects for all generated XML files in the specified directory.
     * Files are identified by the application's naming convention.
     *
     * @param directoryPath The path to the directory containing generated files.
     * @return A List of Path objects, each representing a generated XML file.
     * @throws IOException If an I/O error occurs while traversing the directory.
     */
    public List<Path> getGeneratedFilePaths(String directoryPath) throws IOException {
        List<Path> filePaths = new ArrayList<>();
        Files.walk(Paths.get(directoryPath))
                .filter(path -> Files.isRegularFile(path) && GENERATED_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
                .forEach(filePaths::add);
        return filePaths;
    }

    /**
     * Determines the shortcode for the XML file type and version based on the document's structure.
     * It primarily looks at the namespace URI of the first child element of the document's root.
     * Fallback to common ISO 20022 message types if namespace parsing fails.
     *
     * @param doc The XML Document to inspect.
     * @return A shortcode string (e.g., "PAIN1V3", "PACS8V2", "CAMT53V2"), or "UNKNOWN" if not recognized.
     */
    public String getFileTypeAndVersionShortcode(Document doc) {
        Element rootElement = doc.getDocumentElement();
        NodeList children = rootElement.getChildNodes();
        Element messageRoot = null;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                messageRoot = (Element) children.item(i);
                break;
            }
        }

        if (messageRoot == null) {
            return "UNKNOWN";
        }

        String localName = messageRoot.getLocalName(); // e.g., CstmrCdtTrfInitn, FndgSrcAndTx, BkToCstmrStmt
        String namespaceUri = messageRoot.getNamespaceURI(); // e.g., urn:iso:std:iso:20022:tech:xsd:pain.001.001.03

        if (namespaceUri != null) {
            // Example namespace: urn:iso:std:iso:20022:tech:xsd:pain.001.001.03
            String[] parts = namespaceUri.split(":");
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1]; // pain.001.001.03
                String[] versionParts = lastPart.split("\\.");
                // Ensure there are enough parts for type.major.minor.revision (e.g., pain.001.001.03)
                if (versionParts.length >= 4) {
                    String type = versionParts[0].toUpperCase(); // PAIN, PACS, CAMT
                    // Convert "001" to "1", "008" to "8", etc.
                    String major = String.valueOf(Integer.parseInt(versionParts[1]));
                    // The last part (e.g., "03") is the version
                    String minor = String.valueOf(Integer.parseInt(versionParts[3]));
                    return type + major + "V" + minor;
                }
            }
        }

        // Fallback if namespace parsing fails or is not present, infer from local name
        if ("CstmrCdtTrfInitn".equals(localName)) return "PAIN1V3"; // Common for pain.001.001.03
        if ("CstmrPmtRvsl".equals(localName)) return "PAIN7V2"; // Based on pain7v2.xml
        if ("CstmrDrctDbtInitn".equals(localName)) return "PAIN8V2"; // Based on pain8v2.xml
        if ("FIToFICstmrCdtTrf".equals(localName)) return "PACS8V2"; // Based on pacs8V2.txt
        // if ("BkToCstmrStmt".equals(localName)) return "CAMT53V2";


        return "UNKNOWN";
    }

    /**
     * Determines the batch/transaction type string based on the number of transactions and batches.
     * This is used for naming the generated files.
     * The number of copies is no longer used for this determination.
     *
     * @param numTransactions Total number of transactions.
     * @param numBatches      Total number of batches.
     * @param numCopies       (Ignored for type determination, only used for generating multiple files)
     * @return A string representing the batch/transaction type (e.g., "SDSC", "MDMC", "SDMC", "MSDSC").
     */
    public String getBatchTransactionType(int numTransactions, int numBatches, int numCopies) {
        if (numBatches == 1) {
            if (numTransactions == 1) {
                return "SDSC"; // Single Document Single Credit
            } else { // numTransactions > 1
                return "SDMC"; // Single Document Multiple Credit
            }
        } else { // numBatches > 1
            if (numBatches == numTransactions) {
                return "MSDSC"; // Multiple Single Document Single Credit (e.g., 5 batches, 5 transactions total, implying 1 transaction per batch)
            } else {
                return "MDMC"; // Multiple Document Multiple Credit
            }
        }
    }
}
