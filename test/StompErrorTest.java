import java.io.*;
import java.net.Socket;

public class StompErrorTest {
    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 7777);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream()) {

            // 1. Connect
            sendFrame(out, "CONNECT\naccept-version:1.2\nhost:localhost\nlogin:ofek\npasscode:123\n\n");
            readFrame(in); // CONNECTED

            // 2. Send invalid frame (SUBSCRIBE without 'id' header)
            System.out.println("--- Sending Invalid SUBSCRIBE (Missing id) ---");
            sendFrame(out, "SUBSCRIBE\ndestination:science\n\n");

            // 3. Read response - expecting ERROR
            readFrame(in);

            // 4. Check if server closed the socket
            System.out.println("Checking if connection is closed by server...");
            if (in.read() == -1) {
                System.out.println("Success: Server closed connection after ERROR.");
            }

        } catch (Exception e) {
            System.out.println("Connection ended: " + e.getMessage());
        }
    }

    private static void sendFrame(OutputStream out, String f) throws IOException {
        out.write((f + "\0").getBytes());
        out.flush();
    }

    private static void readFrame(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != 0 && ch != -1)
            sb.append((char) ch);
        if (sb.length() > 0)
            System.out.println("\nServer Sent:\n" + sb.toString());
    }
}