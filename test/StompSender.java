import java.io.*;
import java.net.Socket;

public class StompSender {
    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 7777);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream()) {

            String user = "user" + System.currentTimeMillis();
            // 1. Connect
            sendFrame(out, "CONNECT\naccept-version:1.2\nhost:localhost\nlogin:" + user + "\npasscode:456\n\n");
            readFrame(in);

            // 1.5. Subscribe (Required by server)
            System.out.println("User2: Subscribing to '/science'...");
            sendFrame(out, "SUBSCRIBE\ndestination:/science\nid:200\nreceipt:sub2\n\n");
            readFrame(in); // Consume receipt

            // 2. Send message to science
            System.out.println("User2: Sending message to '/science'...");
            sendFrame(out, "SEND\ndestination:/science\n\nHello from User 2!\n");

            Thread.sleep(1000); // Wait a moment to ensure message sent
            System.out.println("User2: Done.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendFrame(OutputStream out, String f) throws IOException {
        out.write((f + "\0").getBytes());
        out.flush();
    }

    private static void readFrame(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != 0 && ch != -1) {
            sb.append((char) ch);
        }
        System.out.println("User2: Received:\n" + sb.toString());
    }
}