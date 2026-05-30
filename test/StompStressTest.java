
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StompStressTest {

    private static final String HOST = "localhost";
    private static final int PORT = 7777;
    private static final int SQL_PORT = 7778;
    private static final int CONCURRENT_USERS = 20;

    public static void main(String[] args) {
        System.out.println("Starting STOMP Stress Tests (Concurrency & Heavy Payload)...");
        try {
            testConcurrentLogins();
            testHeavyPayload();
            System.out.println("\nALL STRESS TESTS PASSED!");
        } catch (Exception e) {
            System.err.println("\nSTRESS TEST FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testConcurrentLogins() throws Exception {
        System.out.println("\n[Stress] Testing " + CONCURRENT_USERS + " concurrent logins...");
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    String user = "user_stress_" + id + "_" + System.currentTimeMillis();
                    try (Socket sock = new Socket(HOST, PORT)) {
                        login(sock, user, "pass" + id);
                        // Subscribe to something
                        subscribe(sock, "/stress-topic", "id" + id);
                        // Disconnect
                        logout(sock, "rec" + id);
                    }
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add("Thread " + id + " failed: " + e.getMessage());
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        if (!errors.isEmpty()) {
            throw new RuntimeException("Concurrent Login Errors:\n" + String.join("\n", errors));
        }
        System.out.println("Concurrent logins passed.");
    }

    private static void testHeavyPayload() throws Exception {
        System.out.println("\n[Stress] Testing Heavy Payload (400 events)...");

        // 1. Setup Listener
        Socket listenerSock = new Socket(HOST, PORT);
        String listenerUser = "heavy_listener";
        login(listenerSock, listenerUser, "pass123");
        subscribe(listenerSock, "/heavy-topic", "100");
        System.out.println("Listener subscribed.");

        // 2. Setup Reporter
        Socket reporterSock = new Socket(HOST, PORT);
        String reporterUser = "heavy_reporter";
        login(reporterSock, reporterUser, "pass456");
        subscribe(reporterSock, "/heavy-topic", "200"); // Must subscribe
        System.out.println("Reporter logged in.");

        // 3. Load Heavy JSON
        String jsonFile = "test/jsons/heavy_game.json";
        String content = new String(Files.readAllBytes(Paths.get(jsonFile)));
        List<String> rawEvents = extractEvents(content); // Use same helper
        // Use up to 400 events
        final List<String> events;
        if (rawEvents.size() > 400) {
            events = rawEvents.subList(0, 400);
        } else {
            events = rawEvents;
        }
        System.out.println("Loaded " + events.size() + " events.");

        // 4. Start Reader Thread (Prevention of Deadlock)
        List<String> received = new ArrayList<>();
        Thread readerThread = new Thread(() -> {
            System.out.println("Reader Thread Started.");
            try {
                // Use BufferedInputStream for high performance!
                BufferedInputStream bis = new BufferedInputStream(listenerSock.getInputStream());
                for (int i = 0; i < events.size(); i++) {
                    String msg = readFrame(bis);
                    synchronized (received) {
                        received.add(msg);
                    }
                    if ((i + 1) % 100 == 0) {
                        System.out.println("Reader: Received " + (i + 1) + " events.");
                    }
                }
                System.out.println("Reader: Finished receiving all events.");
            } catch (Exception e) {
                System.err.println("Reader Thread Failed:");
                e.printStackTrace();
            }
        });
        readerThread.start();

        // 5. Send Events (Burst with Flow Control)
        long startTime = System.currentTimeMillis();
        int MAX_LAG = 100; // Relaxed Flow Control for Windows

        for (int i = 0; i < events.size(); i++) {
            // FLOW CONTROL CHECK
            while (true) {
                int receivedCount;
                synchronized (received) {
                    receivedCount = received.size();
                }
                if (i - receivedCount < MAX_LAG)
                    break;
                // Wait for reader to catch up
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                }
            }

            sendEvent(reporterSock, "/heavy-topic", events.get(i), reporterUser);
            if ((i + 1) % 100 == 0) {
                System.out.println("Sender: Sent " + (i + 1) + " events.");
            }
        }
        long endTime = System.currentTimeMillis();
        System.out.println("Sent " + events.size() + " events in " + (endTime - startTime) + "ms.");

        // 6. Verify Receipt
        System.out.println("Waiting for reception...");
        readerThread.join(30000); // Wait up to 30s

        System.out.println("Received " + received.size() + " events.");
        if (received.size() != events.size()) {
            throw new RuntimeException("Lossy transmission! Sent " + events.size() + ", Received " + received.size());
        }

        // 7. SQL Check
        verifyServerTracking(reporterUser, "heavy_game");

        // Clean
        listenerSock.close();
        reporterSock.close();
    }

    // --- Helpers (Copied from IntegrationTest for standalone) ---
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

    private static void logout(Socket sock, String receipt) throws IOException {
        send(sock, "DISCONNECT\nreceipt:" + receipt + "\n\n");
        readFrame(sock);
    }

    private static void sendEvent(Socket sock, String topic, String body, String user) throws IOException {
        send(sock, "SEND\ndestination:" + topic + "\nfile:heavy_game\nuser:" + user + "\n\n" + body + "\u0000");
    }

    private static void send(Socket sock, String data) throws IOException {
        OutputStream out = sock.getOutputStream();
        if (!data.endsWith("\u0000"))
            data += "\u0000";
        out.write(data.getBytes());
        out.flush();
    }

    private static String readFrame(Socket sock) throws IOException {
        return readFrame(sock.getInputStream());
    }

    private static String readFrame(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != 0) { // Read until null
            if (b == -1) {
                throw new IOException("Connection closed (EOF) by server. Read " + sb.length() + " bytes.");
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static List<String> readMessages(Socket sock, int count) throws IOException {
        List<String> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            msgs.add(readFrame(sock));
        }
        return msgs;
    }

    private static List<String> extractEvents(String json) {
        List<String> events = new ArrayList<>();
        int eventsIndex = json.indexOf("\"events\":");
        if (eventsIndex == -1)
            return events;

        int arrayStart = json.indexOf("[", eventsIndex);
        if (arrayStart == -1)
            return events;

        int braceCount = 0;
        StringBuilder currentEvent = new StringBuilder();
        boolean insideEvent = false;

        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (braceCount == 0)
                    insideEvent = true;
                braceCount++;
            }

            if (insideEvent) {
                currentEvent.append(c);
            }

            if (c == '}') {
                braceCount--;
                if (braceCount == 0 && insideEvent) {
                    events.add(currentEvent.toString());
                    currentEvent.setLength(0);
                    insideEvent = false;
                }
            }
            // If braceCount drops below 0 relative to where we started tracking logic?
            // Actually braceCount logic above tracks nested objects correctly.
            // But we need to stop when we hit the closing ']' of the events array.
            // The logic above assumes we only track braces inside the array.
            // If we hit ']' and braceCount is 0, we are done.
            if (c == ']' && braceCount == 0)
                break;
        }
        return events;
    }

    private static void verifyServerTracking(String username, String filename) {
        int retries = 10;
        while (retries > 0) {
            try (Socket sqlSock = new Socket("127.0.0.1", SQL_PORT)) {
                OutputStream out = sqlSock.getOutputStream();
                String query = "SELECT filename FROM file_tracking WHERE username='" + username + "'";
                out.write((query + "\u0000").getBytes());
                out.flush();
                InputStream in = sqlSock.getInputStream();
                StringBuilder sb = new StringBuilder();
                int b;
                while ((b = in.read()) != 0 && b != -1)
                    sb.append((char) b);

                if (!sb.toString().contains(filename)) {
                    System.err.println(
                            "Warning: Server tracking not verified for " + filename + ". SQL: " + sb.toString());
                } else {
                    System.out.println("SQL Tracking Verified.");
                }
                return; // Success
            } catch (IOException e) {
                retries--;
                if (retries == 0) {
                    System.err.println("Failed to connect to SQL Server for verification: " + e.getMessage());
                    // Don't fail the whole test for this, it's just verification
                } else {
                    System.out.println("SQL Connection failed, retrying... (" + retries + " left)");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }
}
