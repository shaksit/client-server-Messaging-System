package bgu.spl.net.impl.stomp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import bgu.spl.net.impl.data.User;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.impl.data.Database;
import bgu.spl.net.impl.data.LoginStatus;
import bgu.spl.net.srv.Connections;
import java.util.concurrent.ConcurrentHashMap;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private Connections<String> connections;
    private boolean shouldTerminate = false;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public String process(String message) {
        String[] lines = message.split("\n");
        if (lines.length == 0)
            return null;

        String command = lines[0];
        System.out.println("[Server] Received command: " + command); // Log command

        Map<String, String> headers = new HashMap<>();

        int i = 1;
        while (i < lines.length && !lines[i].isEmpty()) {
            String[] parts = lines[i].split(":", 2);
            if (parts.length == 2) {
                headers.put(parts[0], parts[1]);
            }
            i++;
        }

        String body = "";
        if (i < lines.length) {
            StringBuilder bodyBuilder = new StringBuilder();
            for (int k = i + 1; k < lines.length; k++) {
                bodyBuilder.append(lines[k]).append("\n");
            }
            body = bodyBuilder.toString();
        }

        switch (command) {
            case "CONNECT":
                handleConnect(headers);
                break;

            case "SUBSCRIBE":
                handleSubscribe(headers);
                break;

            case "UNSUBSCRIBE":
                handleUnsubscribe(headers);
                break;

            case "SEND":
                handleSend(headers, body);
                break;

            case "DISCONNECT":
                System.out.println("[Server] Handling DISCONNECT for connectionId: " + connectionId); // Log disconnect
                shouldTerminate = true;
                Database.getInstance().logout(connectionId);
                connections.send(connectionId, "RECEIPT\nreceipt-id:" + headers.get("receipt") + "\n\n");
                connections.disconnect(connectionId);
                break;

            default:
                System.out.println("[Server] Unknown command received: " + command);
                break;
        }

        return null;
    }

    private void handleSubscribe(Map<String, String> headers) {
        String topic = headers.get("destination");
        String id = headers.get("id");
        String receipt = headers.get("receipt");

        System.out.println("[Server] Handling SUBSCRIBE to topic: " + topic + " with id: " + id); // Log subscribe

        if (topic == null || id == null) {
            connections.send(connectionId,
                    "ERROR\nmessage:Malformed Frame\n\nMust contain destination and id headers");
            connections.disconnect(connectionId);
            shouldTerminate = true;
            return;
        }

        connections.subscribe(connectionId, topic, id);

        if (receipt != null) {
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + receipt + "\n\n");
        }
    }

    private void handleUnsubscribe(Map<String, String> headers) {
        String id = headers.get("id");
        String receipt = headers.get("receipt");

        System.out.println("[Server] Handling UNSUBSCRIBE id: " + id); // Log unsubscribe

        if (id == null) {
            connections.send(connectionId, "ERROR\nmessage:Malformed Frame\n\nMust contain id header");
            connections.disconnect(connectionId);
            shouldTerminate = true;
            return;
        }

        connections.unsubscribe(connectionId, id);

        if (receipt != null) {
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + receipt + "\n\n");
        }
    }

    private void handleSend(Map<String, String> headers, String body) {
        String destination = headers.get("destination");
        System.out.println("[Server] Handling SEND to destination: " + destination); // Log send

        if (destination == null) {
            connections.send(connectionId, "ERROR\nmessage:Malformed Frame\n\nMust contain destination header");
            connections.disconnect(connectionId);
            shouldTerminate = true;
            return;
        }

        // Check if user is subscribed to the topic
        if (!connections.isSubscribed(connectionId, destination)) {
            connections.send(connectionId,
                    "ERROR\nmessage:Permission Denied\n\nYou are not subscribed to " + destination);
            connections.disconnect(connectionId);
            shouldTerminate = true;
            return;
        }

        // Track file upload if 'file' header exists
        String filename = headers.get("file");
        if (filename != null) {
            String username = Database.getInstance().getUsername(connectionId);
            if (username != null) {
                Database.getInstance().trackFileUpload(username, filename, destination);
                System.out.println("[Server] Tracked file upload: " + filename + " by user: " + username); // Log file upload
            }
        }

        String msg = "MESSAGE\n" +
                "destination:" + destination + "\n" +
                "message-id:" + java.util.UUID.randomUUID().toString() + "\n" +
                "\n" +
                body + "";

        connections.send(destination, msg);
    }

    private void handleConnect(Map<String, String> headers) {
        String login = headers.get("login");
        String passcode = headers.get("passcode");

        System.out.println("[Server] Handling CONNECT for user: " + login); // Log connect

        if (login == null || passcode == null) {
            connections.send(connectionId,
                    "ERROR\nmessage:Malformed Frame\n\nMust contain login and passcode headers");
            connections.disconnect(connectionId);
            shouldTerminate = true;
            return;
        }

        LoginStatus status = Database.getInstance().login(connectionId, login, passcode);

        switch (status) {
            case ADDED_NEW_USER:
                connections.send(connectionId, "CONNECTED\nversion:1.2\n\n");
                System.out.println("[Server] Added and connected a new user: " + login); // Log connect
                break;
            case LOGGED_IN_SUCCESSFULLY:
                connections.send(connectionId, "CONNECTED\nversion:1.2\n\n");
                System.out.println("[Server] Connected a new user: " + login); // Log connect   
                break;
            case ALREADY_LOGGED_IN:
                connections.send(connectionId,
                        "ERROR\nmessage:User already logged in\n\nUser " + login + " is already logged in");
                connections.disconnect(connectionId);
                shouldTerminate = true;
                System.out.println("[Server] The user " + login + " was already logged in."); // Log connect
                break;

            case WRONG_PASSWORD:
                connections.send(connectionId, "ERROR\nmessage:Wrong password\n\nPassword does not match");
                connections.disconnect(connectionId);
                shouldTerminate = true;
                System.out.println("[Server] User:  " + login + " has entered the wrong password."); // Log connect
                break;

            case CLIENT_ALREADY_CONNECTED:
                connections.disconnect(connectionId);
                shouldTerminate = true;
                System.out.println("[Server] User:  " + login + " was already connected."); // Log connect
                break;
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }
}
