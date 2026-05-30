package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;

public class StompServer {

    public static void main(String[] args) {

        if (args.length < 2) {
            throw new IllegalArgumentException("Insufficient arguments. Usage: <port> <server type>");
        }

        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port format: " + args[0]);
        }

        switch (args[1]) {
            case "tpc":
                Server.threadPerClient(port,
                        () -> new StompMessagingProtocolImpl(),
                        () -> new StompEncoderDecoder())
                        .serve();

                break;
            case "reactor":
                Server.reactor(4, port,
                        () -> new StompMessagingProtocolImpl(),
                        () -> new StompEncoderDecoder())
                        .serve();

                break;
            default:
                throw new IllegalArgumentException("Invalid server type: " + args[1]);
        }

    }
}
