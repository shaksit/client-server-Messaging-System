package bgu.spl.net.impl.stomp; // Ensure this package matches where you put the file

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import bgu.spl.net.srv.ConnectionHandler;
import bgu.spl.net.srv.Connections;

public class ConnectionsImpl<T> implements Connections<T> {

    // Singleton Instance
    private static class InstanceHolder {
        private static final ConnectionsImpl<String> instance = new ConnectionsImpl<>();
    }

    public static ConnectionsImpl<String> getInstance() {
        return InstanceHolder.instance;
    }

    // Data structures
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Integer>> topics = new ConcurrentHashMap<>(); // Topic
                                                                                                                // ->
                                                                                                                // List
                                                                                                                // of
                                                                                                                // Subscribers
                                                                                                                // (ConnectionIDs)
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<String, String>> clientSubscriptions = new ConcurrentHashMap<>();// ConnectionID
                                                                                                                                // ->
                                                                                                                                // (Topic
                                                                                                                                // ->
                                                                                                                                // SubscriptionID)

    private AtomicInteger nextConnectionId = new AtomicInteger(0);

    public int connect(ConnectionHandler<T> ConnectionHandler) {
        if (ConnectionHandler != null) {
            int id = nextConnectionId.getAndIncrement();
            connections.put(id, ConnectionHandler);
            clientSubscriptions.put(id, new ConcurrentHashMap<>());
            return id;
        }
        return -1;
    }

    public boolean subscribe(int connectionId, String topic, String subscriptionId) {
        ConcurrentHashMap<String, String> clientSubs = clientSubscriptions.get(connectionId);
        if (clientSubs != null) {
            clientSubs.put(topic, subscriptionId);
            topics.computeIfAbsent(topic, k -> new ConcurrentLinkedQueue<>()).add(connectionId);
            System.out.println(
                    "[Debug] Client " + connectionId + " subscribed to " + topic + " with subId: " + subscriptionId); // Log send
            return true;
        }
        return false;
    }

    public boolean unsubscribe(int connectionId, String subscriptionId) {
        ConcurrentHashMap<String, String> clientSubs = clientSubscriptions.get(connectionId);
        if (clientSubs != null) {
            // Find topic by subscriptionId
            String topicToRemove = null;
            for (java.util.Map.Entry<String, String> entry : clientSubs.entrySet()) {
                if (entry.getValue().equals(subscriptionId)) {
                    topicToRemove = entry.getKey();
                    break;
                }
            }

            if (topicToRemove != null) {
                clientSubs.remove(topicToRemove);
                ConcurrentLinkedQueue<Integer> subscribers = topics.get(topicToRemove);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                }
                System.out.println("[Debug] Client " + connectionId + " unsubscribed from " + topicToRemove); // Log send
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean send(int connectionId, T msg) {
        ConnectionHandler<T> handler = connections.get(connectionId);
        if (handler != null) {
            handler.send(msg);
            return true;
        }
        return false;
    }

    @Override
    public void send(String channel, T msg) {
        ConcurrentLinkedQueue<Integer> subscribers = topics.get(channel);
        if (subscribers != null) {
            System.out.println(
                    "[Debug] Broadcasting to channel: " + channel + " - Subscribers count: " + subscribers.size());  // Log send
            for (Integer connectionId : subscribers) {
                // Construct personalized message
                String subscriptionId = null;
                ConcurrentHashMap<String, String> clientSubs = clientSubscriptions.get(connectionId);
                if (clientSubs != null) {
                    subscriptionId = clientSubs.get(channel);
                }

                System.out.println("[Debug] Sending to client " + connectionId + " (SubId: " + subscriptionId + ")");  // Log send

                if (subscriptionId != null && msg instanceof String) {
                    String originalMsg = (String) msg;

                    String personalizedMsg = originalMsg.replaceFirst("\n", "\nsubscription:" + subscriptionId + "\n");

                    @SuppressWarnings("unchecked")
                    T castedMsg = (T) personalizedMsg;
                    send(connectionId, castedMsg);
                } else {
                    send(connectionId, msg);
                }
            }
        } else {
            System.out.println("[Debug] No subscribers found for channel: " + channel); // Log send
        }
    }

    @Override
    public void disconnect(int connectionId) {
        ConcurrentHashMap<String, String> clientSubs = clientSubscriptions.remove(connectionId);
        if (clientSubs != null) {
            for (String topic : clientSubs.keySet()) {
                ConcurrentLinkedQueue<Integer> subscribers = topics.get(topic);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                }
            }
        }
        connections.remove(connectionId);
    }

    @Override
    public boolean isSubscribed(int connectionId, String channel) {
        ConcurrentHashMap<String, String> subs = clientSubscriptions.get(connectionId);
        return subs != null && subs.containsKey(channel);
    }
}
