// --- XmlGeneratorApplication.java ---
// Located at: src/main/java/com/example/xmlgenerator/XmlGeneratorApplication.java
package com.example.xmlgenerator;

import com.example.xmlgenerator.service.XmlProcessorService;
import com.example.xmlgenerator.service.GeneratedFile;
import com.example.xmlgenerator.service.GenerationResult;
import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID; // For generating unique IDs for cached files
import java.util.concurrent.ConcurrentHashMap; // For in-memory caching
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

    private final XmlProcessorService xmlProcessorService; // Service for XML processing

    // Date format for ZIP file naming convention: YYYYMMDDHHmmss
    private static final SimpleDateFormat ZIP_FILE_TIMESTAMP_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");

    // In-memory cache for generated ZIP files.
    // Key: Unique ID (UUID), Value: byte array of the zipped content.
    private final ConcurrentHashMap<String, byte[]> generatedZipCache = new ConcurrentHashMap<>();
    // In-memory cache for ZIP file names, associated with the unique ID.
    private final ConcurrentHashMap<String, String> generatedZipNames = new ConcurrentHashMap<>();


    /**
     * Constructor for XmlGeneratorApplication.
     * Initializes the NanoHTTPD server on the specified port and sets up the XML processor service.
     * @throws IOException If the server cannot be started.
     */
    public XmlGeneratorApplication() throws IOException {
        super(PORT); // Call the superclass constructor with the port number.
        this.xmlProcessorService = new XmlProcessorService(); // Initialize the XML processor service.
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); // Start the server.
        System.out.println("Server started on port " + PORT + ". Access http://localhost:" + PORT);
    }

    /**
     * Main method to start the XML Generator application.
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        try {
            new XmlGeneratorApplication(); // Create and start a new instance of the application.
        } catch (IOException e) {
            System.err.println("Could not start server: " + e.getMessage()); // Log any errors during server startup.
        }
    }

    /**
     * Handles HTTP requests. This is the core method of NanoHTTPD where all incoming requests are processed.
     * It routes requests to appropriate handler methods based on the URI.
     *
     * @param session The HTTP session containing request details.
     * @return A NanoHTTPD.Response object representing the HTTP response.
     */
    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod(); // Get the HTTP method (GET, POST, etc.)
        String uri = session.getUri(); // Get the requested URI

        System.out.println(method + " '" + uri + "'"); // Log the incoming request

        try {
            // Route requests based on URI and method
            if (Method.GET.equals(method)) {
                if ("/".equals(uri) || "/index.html".equals(uri)) {
                    return handleIndexPage(); // Serve the main HTML page
                } else if ("/result.html".equals(uri)) {
                    // Serve the result page. It will use client-side JS to get download link.
                    return serveStaticFile("/result.html");
                } else if (uri.startsWith("/download-generated-files")) {
                    // New endpoint to handle actual file download from cache
                    return handleDownloadRequest(session);
                }
                else {
                    // Serve other static files (CSS, JS, images)
                    return serveStaticFile(uri);
                }
            } else if (Method.POST.equals(method) && "/generate".equals(uri)) {
                return handleGenerateRequest(session); // Handle XML file generation request
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
            // Return an internal server error response if an unexpected exception occurs.
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error: " + e.getMessage());
        }

        // Default response for unhandled requests
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
    }

    /**
     * Serves the main index.html page.
     *
     * @return A NanoHTTPD.Response object for the index page.
     */
    private Response handleIndexPage() {
        try {
            // Read the index.html file from the classpath (resources folder).
            InputStream inputStream = getClass().getResourceAsStream("/index.html");
            if (inputStream == null) {
                // If the file is not found, return a 404 Not Found response.
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "index.html not found.");
            }
            // Return a new response with the content of index.html and appropriate MIME type.
            return newFixedLengthResponse(Response.Status.OK, "text/html", inputStream, inputStream.available());
        } catch (IOException e) {
            System.err.println("Error serving index.html: " + e.getMessage());
            e.printStackTrace();
            // Return an internal server error response if an I/O error occurs.
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error loading index.html.");
        }
    }

    /**
     * Serves static files (e.g., CSS, JavaScript, images) from the classpath.
     *
     * @param uri The URI of the requested static file.
     * @return A NanoHTTPD.Response object for the static file.
     */
    private Response serveStaticFile(String uri) {
        try {
            // Construct the path to the resource.
            // Remove leading slash for resourceAsStream.
            String resourcePath = uri.startsWith("/") ? uri.substring(1) : uri;
            InputStream inputStream = getClass().getResourceAsStream("/" + resourcePath);

            if (inputStream == null) {
                // If the resource is not found, return a 404 Not Found response.
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found: " + uri);
            }

            // Determine MIME type based on file extension.
            String mimeType = getMimeType(uri);
            // Return a new response with the content of the file and appropriate MIME type.
            return newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, inputStream.available());
        } catch (IOException e) {
            System.err.println("Error serving static file " + uri + ": " + e.getMessage());
            e.printStackTrace();
            // Return an internal server error response if an I/O error occurs.
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error loading file: " + uri);
        }
    }

    /**
     * Handles the POST request for XML file generation.
     * It parses form data, calls the XML processing service, zips the generated files in memory,
     * stores them in a cache, and redirects the user to the result page with a download ID.
     *
     * @param session The HTTP session containing request details, including form data.
     * @return A NanoHTTPD.Response object for redirection or an error message.
     */
    private Response handleGenerateRequest(IHTTPSession session) {
        try {
            // Parse the multipart form data.
            Map<String, String> files = new HashMap<>(); // To store file content (temp file paths)
            session.parseBody(files); // Parse the body into files map (for uploaded files) and parameters

            // Get form parameters
            String numTransactionsStr = session.getParms().get("numTransactions");
            String numBatchesStr = session.getParms().get("numBatches");
            String numCopiesStr = session.getParms().get("numCopies");

            // Validate and convert parameters to integers
            int numTransactions = Integer.parseInt(numTransactionsStr);
            int numBatches = Integer.parseInt(numBatchesStr);
            int numCopies = Integer.parseInt(numCopiesStr);

            // Get the uploaded template file content
            String templateTempFilePath = files.get("templateFile"); 
            if (templateTempFilePath == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Template file not uploaded.");
            }

            // Read the uploaded template file into an InputStream from its temporary location
            InputStream templateInputStream = new FileInputStream(templateTempFilePath);

            // Generate XML files in memory and get the GenerationResult
            GenerationResult generationResult = xmlProcessorService.generateXmlFiles(templateInputStream, numTransactions, numBatches, numCopies);
            List<GeneratedFile> generatedFiles = generationResult.getFiles();
            
            // Close the template input stream after processing
            templateInputStream.close();

            if (generatedFiles.isEmpty()) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No XML files were generated.");
            }

            // Construct the ZIP file name
            String zipFileName = generationResult.getFileTypeShortcode() + "_" + 
                                 generationResult.getBatchTransactionType() + "_" + 
                                 ZIP_FILE_TIMESTAMP_FORMAT.format(new Date()) + ".zip";

            // Create a ZIP archive in memory
            ByteArrayOutputStream zipOutputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(zipOutputStream)) {
                for (GeneratedFile generatedFile : generatedFiles) {
                    ZipEntry entry = new ZipEntry(generatedFile.getFileName());
                    zos.putNextEntry(entry);
                    zos.write(generatedFile.getContent());
                    zos.closeEntry();
                }
            }

            byte[] zipBytes = zipOutputStream.toByteArray();

            // Generate a unique ID for this generated ZIP file
            String downloadId = UUID.randomUUID().toString();
            generatedZipCache.put(downloadId, zipBytes); // Store the ZIP content in cache
            generatedZipNames.put(downloadId, zipFileName); // Store the ZIP filename in cache

            // Redirect to result.html, passing the download ID and filename as query parameters
            Response response = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/html", "Redirecting to download page...");
            response.addHeader("Location", "/result.html?id=" + downloadId + "&filename=" + zipFileName);
            return response;

        } catch (NumberFormatException e) {
            System.err.println("Invalid number format for input parameters: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid number format for transactions, batches, or copies.");
        } catch (Exception e) {
            System.err.println("Error during file generation: " + e.getMessage());
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error generating files: " + e.getMessage());
        }
    }

    /**
     * Handles the GET request for downloading a generated ZIP file from the in-memory cache.
     *
     * @param session The HTTP session containing request details, including query parameters.
     * @return A NanoHTTPD.Response object for the download or an error message.
     */
    private Response handleDownloadRequest(IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        String downloadId = parms.get("id");
        String requestedFileName = parms.get("filename"); // Get filename from URL for Content-Disposition

        if (downloadId == null || !generatedZipCache.containsKey(downloadId)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Download link invalid or expired.");
        }

        byte[] zipBytes = generatedZipCache.remove(downloadId); // Retrieve and remove from cache (single use)
        String actualFileName = generatedZipNames.remove(downloadId); // Retrieve and remove filename

        if (zipBytes == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found in cache or already downloaded.");
        }

        // Use the actual filename from the cache if available, otherwise fallback to requested or a default.
        String fileNameToUse = (actualFileName != null && !actualFileName.isEmpty()) ? actualFileName : 
                               (requestedFileName != null && !requestedFileName.isEmpty() ? requestedFileName : "generated_files.zip");

        Response response = newFixedLengthResponse(Response.Status.OK, "application/zip", new ByteArrayInputStream(zipBytes), zipBytes.length);
        response.addHeader("Content-Disposition", "attachment; filename=" + fileNameToUse);
        return response;
    }


    /**
     * Helper method to determine the MIME type based on the file extension.
     *
     * @param uri The URI of the file.
     * @return The MIME type string.
     */
    private String getMimeType(String uri) {
        if (uri.endsWith(".html")) return "text/html";
        if (uri.endsWith(".css")) return "text/css";
        if (uri.endsWith(".js")) return "application/javascript";
        if (uri.endsWith(".png")) return "image/png";
        if (uri.endsWith(".jpg") || uri.endsWith(".jpeg")) return "image/jpeg";
        if (uri.endsWith(".gif")) return "image/gif";
        if (uri.endsWith(".xml")) return "application/xml"; // For direct XML viewing if needed
        if (uri.endsWith(".zip")) return "application/zip";
        return MIME_PLAINTEXT; // Default to plain text
    }
    
    private static final Response.IStatus SEE_OTHER = new Response.IStatus() {
        @Override
        public int getRequestStatus() {
            return 303;
        }

        @Override
        public String getDescription() {
            return "See Other";
        }
    };
}
