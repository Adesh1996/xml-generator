// --- XmlGeneratorApplication.java ---
// Located at: src/main/java/com/example/xmlgenerator/XmlGeneratorApplication.java
package com.example.xmlgenerator;

import com.example.xmlgenerator.service.XmlProcessorService;
import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Main application class extending NanoHTTPD to serve web content and handle XML generation.
 * This class acts as the embedded web server, handling HTTP requests and responses.
 */
public class XmlGeneratorApplication extends NanoHTTPD {

    // --- PORT MODIFICATION ---
    // Change this value to your desired port number.
    private static final int PORT = 9090; // Port for the HTTP server
    // --- END PORT MODIFICATION ---

    private static final String GENERATED_FILES_DIR = "generated_files"; // Directory for generated files
    private final XmlProcessorService xmlProcessorService; // Service for XML processing

    /**
     * Constructor for XmlGeneratorApplication.
     * Initializes the NanoHTTPD server on the specified port and sets up the XML processor service.
     * @throws IOException If the server cannot be started.
     */
    public XmlGeneratorApplication() throws IOException {
        super(PORT); // Call NanoHTTPD constructor with the port
        this.xmlProcessorService = new XmlProcessorService(); // Initialize the XML processing service
        // Start the HTTP server
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        System.out.println("NanoHTTPD server started on port " + PORT);

        // Ensure the generated files directory exists
        try {
            Files.createDirectories(Paths.get(GENERATED_FILES_DIR));
        } catch (IOException e) {
            System.err.println("Error creating generated files directory: " + e.getMessage());
        }
    }

    /**
     * Main method to start the application.
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        try {
            new XmlGeneratorApplication(); // Create and start the application instance
        } catch (IOException e) {
            System.err.println("Could not start server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Overrides the serve method from NanoHTTPD to handle incoming HTTP requests.
     * This method acts as the central router for all web requests.
     * @param session The HTTP session containing request details.
     * @return A NanoHTTPD.Response object containing the HTTP response.
     */
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri(); // Get the requested URI
        Method method = session.getMethod(); // Get the HTTP method (GET, POST, etc.)

        // Handle GET requests for static files and initial page load
        if (Method.GET.equals(method)) {
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                // Serve the index.html page
                return serveHtmlFile("templates/index.html", null); // No dynamic content on initial load
            } else if ("/result.html".equals(uri)) {
                // Serve the result.html page (should generally be reached via POST redirect)
                // This path is mainly for direct refresh, so no dynamic content is passed by default.
                return serveHtmlFile("templates/result.html", null); // No dynamic content on direct refresh
            } else if ("/download".equals(uri)) {
                // Handle file download request
                return handleDownloadRequest();
            }
        } else if (Method.POST.equals(method) && "/generate".equals(uri)) {
            // Handle POST request for file generation
            return handleGenerateRequest(session);
        }

        // Return a 404 Not Found response for any unhandled URIs
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
    }

    /**
     * Serves an HTML file from the resources directory.
     * @param filePath The path to the HTML file within resources (e.g., "templates/index.html").
     * @param dynamicData A map of data to inject into the HTML (e.g., "message", "timeTaken", "errorMessage").
     * @return A NanoHTTPD.Response object with the HTML content.
     */
    private Response serveHtmlFile(String filePath, Map<String, String> dynamicData) {
        try {
            InputStream inputStream = getClass().getResourceAsStream("/" + filePath);
            if (inputStream == null) {
                System.err.println("Error: HTML template not found: " + filePath);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error: Template not found.");
            }

            String htmlContent = readInputStreamToString(inputStream);

            // Process dynamic content for index.html
            if ("templates/index.html".equals(filePath)) {
                String messageHtml = "";
                if (dynamicData != null && dynamicData.containsKey("message")) {
                    String message = dynamicData.get("message");
                    boolean isSuccess = "true".equals(dynamicData.get("isSuccess"));
                    String messageClass = isSuccess ? "success" : "error";
                    messageHtml = "<div class=\"message " + messageClass + "\"><p>" + message + "</p></div>";
                }
                htmlContent = htmlContent.replace("[[${message_html}]]", messageHtml);
            }
            // Process dynamic content for result.html
            else if ("templates/result.html".equals(filePath)) {
                String resultContentHtml = "";
                if (dynamicData != null && dynamicData.containsKey("errorMessage")) {
                    resultContentHtml = "<div class=\"message error\"><p>" + dynamicData.get("errorMessage") + "</p></div>";
                } else if (dynamicData != null && dynamicData.containsKey("timeTaken")) {
                    String timeTaken = dynamicData.get("timeTaken");
                    resultContentHtml =
                        "<p>Time taken: " + timeTaken + " ms</p>" +
                        "<p>Your files are ready for download.</p>" +
                        "<div class=\"button-group\">" +
                        "<a href=\"/download\" class=\"download-button\">Download Now</a>" +
                        "<a href=\"/\" class=\"refresh-button\">Refresh</a>" +
                        "</div>";
                }
                htmlContent = htmlContent.replace("[[${result_content_html}]]", resultContentHtml);
                System.out.println("DEBUG: Result HTML content generated:\n" + htmlContent.substring(0, Math.min(htmlContent.length(), 500)) + "..."); // Print first 500 chars
            }

            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, htmlContent);
        } catch (IOException e) {
            System.err.println("Error reading HTML file: " + e.getMessage());
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error");
        }
    }

    /**
     * Reads the content of an InputStream into a String.
     * @param inputStream The InputStream to read.
     * @return The content of the InputStream as a String.
     * @throws IOException If an I/O error occurs.
     */
    private String readInputStreamToString(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString("UTF-8"); // Specify UTF-8 encoding
    }

    /**
     * Handles the POST request for file generation.
     * Parses the multipart form data, calls the XML processing service, and redirects to the result page.
     * @param session The HTTP session.
     * @return A NanoHTTPD.Response object, typically a redirect or an error page.
     */
  public Response handleGenerateRequest(IHTTPSession session) {
    long startTime = System.currentTimeMillis();
    Map<String, String> files = new HashMap<String, String>();

    try {
        session.parseBody(files);
        Map<String, List<String>> parameters = session.getParameters();

        String numTransactionsStr = parameters.get("numTransactions").get(0);
        String numBatchesStr = parameters.get("numBatches").get(0);
        String numCopiesStr = parameters.get("numCopies").get(0);

        int numTransactions = Integer.parseInt(numTransactionsStr);
        int numBatches = Integer.parseInt(numBatchesStr);
        int numCopies = Integer.parseInt(numCopiesStr);

        String templateFileTempPath = files.get("templateFile");
        System.out.println("DEBUG: templateFileTempPath received: " + templateFileTempPath);

        if (templateFileTempPath == null || templateFileTempPath.isEmpty()) {
            return serveHtmlFile("templates/index.html", createMessageMap("message", "Please upload a template XML file.", false));
        }

        if (numTransactions <= 0 || numBatches <= 0 || numCopies <= 0) {
            return serveHtmlFile("templates/index.html", createMessageMap("message", "Number of transactions, batches, and copies must be positive.", false));
        }

        InputStream templateInputStream = new FileInputStream(templateFileTempPath);

        xmlProcessorService.cleanupGeneratedFiles(GENERATED_FILES_DIR);
        xmlProcessorService.generateXmlFiles(templateInputStream, numTransactions, numBatches, numCopies, GENERATED_FILES_DIR);

        long endTime = System.currentTimeMillis();
        Map<String, String> resultParams = new HashMap<>();
        resultParams.put("timeTaken", String.valueOf(endTime - startTime));
        return serveHtmlFile("templates/result.html", resultParams);

    } catch (NumberFormatException e) {
        System.err.println("Error parsing number: " + e.getMessage());
        return serveHtmlFile("templates/index.html", createMessageMap("message", "Invalid number format for transactions, batches, or copies.", false));
    } catch (Exception e) {
        System.err.println("Error during file generation: " + e.getMessage());
        e.printStackTrace();
        Map<String, String> data = new HashMap<String, String>();
        data.put("errorMessage", "Error generating files: " + e.getMessage());
        return serveHtmlFile("templates/result.html", data);
    } finally {
        String templateFileTempPath = files.get("templateFile");
        if (templateFileTempPath != null) {
            new File(templateFileTempPath).delete();
        }
    }
}


    /**
     * Handles the file download request.
     * Zips multiple files or downloads a single file.
     * @return A NanoHTTPD.Response object with the file(s) for download.
     */
    private Response handleDownloadRequest() {
        try {
            List<Path> files = xmlProcessorService.getGeneratedFilePaths(GENERATED_FILES_DIR);

            if (files.isEmpty()) {
                System.err.println("No files found to download in " + GENERATED_FILES_DIR);
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No files generated yet.");
            }

            if (files.size() == 1) {
                // Serve a single file
                Path filePath = files.get(0);
                byte[] fileBytes = Files.readAllBytes(filePath);
                Response response = newFixedLengthResponse(Response.Status.OK, "application/xml", new ByteArrayInputStream(fileBytes), fileBytes.length);
                response.addHeader("Content-Disposition", "attachment; filename=" + filePath.getFileName().toString());
                return response;
            } else {
                // Serve multiple files as a ZIP archive
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                    for (Path path : files) {
                        ZipEntry entry = new ZipEntry(path.getFileName().toString());
                        zos.putNextEntry(entry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    }
                }
                byte[] zipBytes = baos.toByteArray();
                Response response = newFixedLengthResponse(Response.Status.OK, "application/zip", new ByteArrayInputStream(zipBytes), zipBytes.length);
                response.addHeader("Content-Disposition", "attachment; filename=generated_xml_files.zip");
                return response;
            }
        } catch (IOException e) {
            System.err.println("Error during file download: " + e.getMessage());
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error downloading files.");
        }
    }

    /**
     * Helper method to create a map for dynamic message content.
     * @param key The key for the message (e.g., "message", "errorMessage").
     * @param value The message text.
     * @param isSuccess True if it's a success message, false for error.
     * @return A Map containing the message and success status.
     */
    private Map<String, String> createMessageMap(String key, String value, boolean isSuccess) {
        Map<String, String> map = new HashMap<String, String>();
        map.put(key, value);
        map.put("isSuccess", String.valueOf(isSuccess));
        return map;
    }
}
