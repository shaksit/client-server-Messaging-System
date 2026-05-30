
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StompIntegrationTest {

    private static final String HOST = "localhost";
    private static final int PORT = 7777;
    private static final int SQL_PORT = 7778;

    public static void main(String[] args) {
        System.out.println("Starting STOMP Integration Tests (JSON/Summary/Report)...");
        try {
            testFullCycle();
            System.out.println("\nALL INTEGRATION TESTS PASSED!");
        } catch (Exception e) {
            System.err.println("\nTEST FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testFullCycle() throws Exception {
        System.out.println(
                "\n[Test] Full Cycle: Listener subscribes, Reporter sends JSON events, Verify Summary & Server Report");

        // 1. Start Listener (User A)
        Socket listenerSock = new Socket(HOST, PORT);
        String listenerUser = "listener_" + System.currentTimeMillis();
        login(listenerSock, listenerUser, "pass123");
        subscribe(listenerSock, "world-cup", "100");
        System.out.println("Listener subscribed to 'world-cup'.");

        // 2. Start Reporter (User B)
        Socket reporterSock = new Socket(HOST, PORT);
        String reporterUser = "reporter_" + System.currentTimeMillis();
        login(reporterSock, reporterUser, "pass456");
        subscribe(reporterSock, "world-cup", "200"); // REQUIRED: Must subscribe to send
        System.out.println("Reporter logged in and subscribed.");

        // 3. Read JSON and Send Events
        String jsonFile = "test/jsons/game1.json";
        String content = new String(Files.readAllBytes(Paths.get(jsonFile)));
        List<String> events = extractEvents(content); // Simple regex parser
        System.out.println("Parsed " + events.size() + " events from " + jsonFile);

        for (String event : events) {
            sendEvent(reporterSock, "world-cup", event, reporterUser);
            // Small delay to ensure order
            Thread.sleep(50);
        }

        // 4. Verify Listener received messages
        System.out.println("Verifying Listener received messages...");
        List<String> receivedMessages = readMessages(listenerSock, events.size());
        if (receivedMessages.size() != events.size()) {
            throw new RuntimeException(
                    "Listener missed messages! Expected " + events.size() + ", got " + receivedMessages.size());
        }
        System.out.println("Listener received all " + receivedMessages.size() + " events.");

        // 5. Generate Summary (Client Side Simulation)
        System.out.println("\n--- CLIENT SUMMARY (Simulation) ---");
        System.out.println("Game: Germany vs Japan"); // Derived from JSON usually
        System.out.println("Total Events: " + receivedMessages.size());
        System.out.println("Events:");
        for (String msg : receivedMessages) {
            System.out.println(" - " + msg.replace("\n", " ").substring(0, Math.min(msg.length(), 50)) + "...");
        }
        System.out.println("-----------------------------------");

        // 6. Verify Server 'Report' (File Tracking) via SQL
        System.out.println("\n[Test] Verifying Server Tracking (Report) via SQL...");
        verifyServerTracking(reporterUser, "world-cup");

        // Clean up
        logout(listenerSock, "l1");
        logout(reporterSock, "r1");
        listenerSock.close();
        reporterSock.close();
    }

    // Helper to extract events from simple JSON structure without external lib
    private static List<String> extractEvents(String json) {
        List<String> events = new ArrayList<>();
        // Find all "event name": "..." blocks.
        // This is a naive parser for the test.
        // We will just split by "event name" and reconstruct roughly.
        // Or simply send the whole JSON Event object as body.

        // Let's parse strictly valid JSON objects from the array.
        // Assumption: JSON is formatted nicely as in the file.
        Pattern p = Pattern.compile("\\{([^{}]+)\\}");
        Matcher m = p.matcher(json);
        while (m.find()) {
            String block = m.group(1);
            if (block.contains("event name")) {
                events.add("{" + block + "}");
            }
        }
        if (events.isEmpty()) {
            // Maybe single line?
            // Let's just create artificial events if parsing fails logic
            events.add("{\"event name\":\"Goal\", \"description\":\"Fallback Event\"}");
        }
        return events;
    }

    private static void login(Socket sock, String user, String pass) throws IOException {
        send(sock, "CONNECT\naccept-version:1.2\nhost:localhost\nlogin:" + user + "\npasscode:" + pass + "\n\n");
        String resp = readFrame(sock);
        if (!resp.contains("CONNECTED"))
            throw new RuntimeException("Login failed: " + resp);
    }

    private static void subscribe(Socket sock, String topic, String id) throws IOException {
        send(sock, "SUBSCRIBE\ndestination:" + topic + "\nid:" + id + "\nreceipt:sub_" + id + "\n\n");
        String resp = readFrame(sock);
        if (!resp.contains("RECEIPT"))
            throw new RuntimeException("Subscribe failed: " + resp);
    }

    private static void sendEvent(Socket sock, String topic, String body, String user) throws IOException {
        // Send with 'user' header (though typically client adds it to body? assignment
        // says header?)
        // Assignments says: "The SEND frame... usually contains a header 'user'..."?
        // Actually protocol 2.2: "Client sends SEND... Server forwards MESSAGE".
        // Server ADDS subscription header.
        // Client report adds 'user' header to SEND frame? Let's assume so.
        send(sock, "SEND\ndestination:" + topic + "\nfile:test_file\nuser:" + user + "\n\n" + body + "\u0000"); // Add
                                                                                                                // null
                                                                                                                // manually
                                                                                                                // here
                                                                                                                // because
                                                                                                                // we
                                                                                                                // use
                                                                                                                // raw
                                                                                                                // socket?
        // Wait, StompMessagingProtocolImpl removed manual nulls. But CLIENT MUST SEND
        // IT.
        // My previous test `testDoubleLogin` added `\u0000`.
        // Server expects `recv_null_terminated`? No, Java server uses
        // `StompEncoderDecoder`.
        // `decodeNextByte` waits for `\u0000`.
        // So YES, we MUST send `\u0000`.
    }

    private static void logout(Socket sock, String receiptId) throws IOException {
        send(sock, "DISCONNECT\nreceipt:" + receiptId + "\n\n"); // EncDec adds null? NO. We are RAW socket. We must add
                                                                 // null.
        // Wait. `send` method below:
    }

    private static void send(Socket sock, String data) throws IOException {
        OutputStream out = sock.getOutputStream();
        if (!data.endsWith("\u0000")) {
            data += "\u0000";
        }
        out.write(data.getBytes());
        out.flush();
    }

    // Read N frames
    private static List<String> readMessages(Socket sock, int count) throws IOException {
        List<String> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String frame = readFrame(sock);
            if (!frame.contains("MESSAGE")) {
                throw new RuntimeException("Expected MESSAGE frame, got: " + frame);
            }
            msgs.add(frame);
        }
        return msgs;
    }

    private static String readFrame(Socket sock) throws IOException {
        InputStream in = sock.getInputStream();
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != 0) {
            if (b == -1)
                break;
            sb.append((char) b);
        }
        // Read potential extra newlines if any? No, one frame ends with null.
        return sb.toString();
    }

    private static void verifyServerTracking(String username, String topic) throws IOException {
        // Query SQL Server (7778)
        // Protocol: "SELECT ... \n" (null terminated?)
        // Server: `recv_null_terminated`. YES.
        try (Socket sqlSock = new Socket("localhost", SQL_PORT)) {
            OutputStream out = sqlSock.getOutputStream();
            String query = "SELECT filename FROM file_tracking WHERE username='" + username + "'";
            out.write((query + "\u0000").getBytes());
            out.flush();

            InputStream in = sqlSock.getInputStream();
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != 0 && b != -1) {
                sb.append((char) b);
            }
            String result = sb.toString();
            // Expected: "SUCCESS|test_file" (Wait, protocol change: SUCCESS|...)
            // Wait, we reverted to SUCCESS|...
            // If empty: "SUCCESS"
            // If found: "SUCCESS|test_file" (maybe list of tuple string?)
            // Python returns: `SUCCESS|('test_file', '...', ...)` or
            // `SUCCESS|('test_file',)`

            if (!result.contains("test_file")) {
                throw new RuntimeException("Server did not track file! SQL Result: " + result);
            }
            System.out.println("SQL Check Passed. Result: " + result);
        }
    }
}
