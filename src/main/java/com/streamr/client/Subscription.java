package com.streamr.client;

import com.streamr.client.utils.StreamPartition;

import java.util.UUID;

public class Subscription {

    private final String id;
    private final StreamPartition streamPartition;

    private State state;

    enum State {
        SUBSCRIBING, SUBSCRIBED, UNSUBSCRIBING, UNSUBSCRIBED
    }

    public Subscription(String streamId, int partition) {
        this.id = UUID.randomUUID().toString();
        this.streamPartition = new StreamPartition(streamId, partition);
    }

    public String getId() {
        return id;
    }

    public String getStreamId() {
        return streamPartition.getStreamId();
    }

    public int getPartition() {
        return streamPartition.getPartition();
    }

    public StreamPartition getStreamPartition() {
        return streamPartition;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }
}
