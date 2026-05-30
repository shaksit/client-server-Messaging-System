package bgu.spl.net.srv;

import java.io.IOException;

public interface Connections<T> {

    boolean send(int connectionId, T msg);

    void send(String channel, T msg);

    void disconnect(int connectionId);

    boolean subscribe(int connectionId, String topic, String subscriptionId);

    boolean unsubscribe(int connectionId, String subscriptionId);

    boolean isSubscribed(int connectionId, String channel);
}
