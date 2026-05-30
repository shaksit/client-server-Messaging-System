
import java.io.*;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class StompEdgeCaseTest {

    private static final String HOST = "localhost";
    private static final int PORT = 7777;

    public static void main(String[] args) {
        System.out.println("Starting STOMP Edge Case Tests...");
        try {
            testDoubleLogin();
            testMissingHeaders();
            testSubscriptionFlow();
            testDisconnectReceipt();
            // testInvalidFrame(); // Optional
            System.out.println("\nALL EDGE CASE TESTS PASSED!");
        } catch (Exception e) {
            System.err.println("\nTEST FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testDoubleLogin() throws Exception {
        System.out.println("\n[Test] Double Login checking...");
        String user = "user" + System.currentTimeMillis();
        Socket sock1 = new Socket(HOST, PORT);
        String loginFrame = "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:" + user
                + "\npasscode:123\n\n\u0000";
        send(sock1, loginFrame);
        String response1 = readFrame(sock1);
        if (!response1.contains("CONNECTED")) {
            throw new RuntimeException("First login failed: " + response1);
        }

        // Try second login with same user on DIFFERENT socket
        Socket sock2 = new Socket(HOST, PORT);
        send(sock2, loginFrame);
        String response2 = readFrame(sock2);

        // Expecting ERROR or immediate disconnect or ALREADY_LOGGED_IN handling
        // (Assignment says: "If the client is already logged in... server should send
        // an ERROR frame")
        if (response2.contains("CONNECTED")) {
            throw new RuntimeException("Second login SHOULD FAIL but got CONNECTED.");
        }
        System.out.println("Double login rejected correctly: " + response2.split("\n")[0]);

        sock1.close();
        sock2.close();
    }

    private static void testMissingHeaders() throws Exception {
        System.out.println("\n[Test] Missing Headers checking...");
        Socket sock = new Socket(HOST, PORT);
        // Valid login first
        String user = "userH_" + System.currentTimeMillis();
        send(sock, "CONNECT\naccept-version:1.2\nhost:localhost\nlogin:" + user + "\npasscode:123\n\n\u0000");
        readFrame(sock);

        // Send SUBSCRIBE without ID
        send(sock, "SUBSCRIBE\ndestination:science\n\n\u0000");
        String response = readFrame(sock);
        if (!response.contains("ERROR")) {
            throw new RuntimeException("Should return ERROR for missing 'id' header, got: " + response);
        }
        System.out.println("Missing header handled correctly.");
        sock.close();
    }

    private static void testSubscriptionFlow() throws Exception {
        System.out.println("\n[Test] Subscription logic checking...");
        Socket sock = new Socket(HOST, PORT);
        String user = "userS_" + System.currentTimeMillis();
        send(sock, "CONNECT\naccept-version:1.2\nhost:localhost\nlogin:" + user + "\npasscode:123\n\n\u0000");
        readFrame(sock);

        // Subscribe with duplicate ID? or just normal subscribe
        // Let's test UNSUBSCRIBE non-existent ID
        send(sock, "UNSUBSCRIBE\nid:99999\n\n\u0000");
        // Server might ignores or sends ERROR? Usually spec doesn't strictly say, but
        // good robust server sends ERROR or ignores.
        // Let's assume ignore is acceptable, or ERROR.
        // But let's test subscribing and verifying receipt.

        send(sock, "SUBSCRIBE\ndestination:science\nid:100\nreceipt:77\n\n\u0000");
        String resp = readFrame(sock);
        if (!resp.contains("RECEIPT") || !resp.contains("receipt-id:77")) {
            throw new RuntimeException("Did not receive RECEIPT for subscription. Got: " + resp);
        }
        System.out.println("Subscription receipt verified.");
        sock.close();
    }

    private static void testDisconnectReceipt() throws Exception {
        System.out.println("\n[Test] Disconnect with Receipt...");
        Socket sock = new Socket(HOST, PORT);
        String user = "userD_" + System.currentTimeMillis();
        send(sock, "CONNECT\naccept-version:1.2\nhost:localhost\nlogin:" + user + "\npasscode:123\n\n\u0000");
        readFrame(sock);

        send(sock, "DISCONNECT\nreceipt:99\n\n\u0000");
        String resp = readFrame(sock);
        if (!resp.contains("RECEIPT") || !resp.contains("receipt-id:99")) {
            throw new RuntimeException("Disconnect receipt missing. Got: " + resp);
        }
        System.out.println("Disconnect receipt verified.");

        // Verify socket closes?
        int ch = sock.getInputStream().read();
        if (ch != -1) {
            System.out.println("Warning: Socket didn't close immediately after receipt, or there is extra data.");
        }
        sock.close();
    }

    private static void send(Socket sock, String data) throws IOException {
        OutputStream out = sock.getOutputStream();
        out.write(data.getBytes());
        out.flush();
    }

    private static String readFrame(Socket sock) throws IOException {
        InputStream in = sock.getInputStream();
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != 0) { // Read until null char
            if (b == -1) {
                if (sb.length() == 0)
                    return "DISCONNECTED_WITHOUT_DATA";
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }
}
