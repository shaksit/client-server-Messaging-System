import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class StompListener {
    public static void main(String[] args) {
        // Use UTF-8 to support special characters
        try (Socket socket = new Socket("localhost", 7777);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream()) {

            // 1. Connect
            System.out.println("User1: Sending CONNECT...");
            String rndUser = "user" + System.currentTimeMillis();
            sendFrame(out, "CONNECT\naccept-version:1.2\nhost:localhost\nlogin:" + rndUser + "\npasscode:123\n\n");

            // Read CONNECTED response first!
            String response = readFrame(in);
            if (response != null) {
                System.out.println("User1: Received:\n" + response);
            } else {
                System.out.println("User1: Server disconnected without response.");
                return;
            }

            // 2. Subscribe
            System.out.println("User1: Sending SUBSCRIBE to '/science'...");
            sendFrame(out, "SUBSCRIBE\ndestination:/science\nid:sub10\n\n");

            System.out.println("User1: Subscribed. Waiting for messages...");

            // 3. Unified listener loop - handles all incoming frames
            while (true) {
                String frame = readFrame(in);
                if (frame == null) {
                    System.out.println("Server disconnected.");
                    break; // Exit loop if socket closed
                }
                if (!frame.isEmpty()) {
                    System.out.println("\n[" + System.currentTimeMillis() + "] --- User1 Received ---");
                    System.out.println("\n--- User1 Received ---\n" + frame);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendFrame(OutputStream out, String f) throws IOException {
        out.write((f + "\0").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int ch;

        // Read bytes until null character
        while ((ch = in.read()) != 0) {
            if (ch == -1)
                return null; // Connection closed
            buffer.write(ch);
        }

        // Convert read bytes to string with correct encoding
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}