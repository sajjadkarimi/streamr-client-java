package com.streamr.client.protocol.message_layer;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;

import java.io.IOException;

public class MessageRefAdapter extends JsonAdapter<MessageRef> {
    @Override
    public MessageRef fromJson(JsonReader reader) throws IOException {
        reader.beginArray();
        long timestamp = reader.nextLong();
        int sequenceNumber = reader.nextInt();
        reader.endArray();
        return new MessageRef(timestamp, sequenceNumber);
    }

    @Override
    public void toJson(JsonWriter writer, MessageRef value) throws IOException {
        writer.beginArray();
        writer.value(value.getTimestamp());
        writer.value(value.getSequenceNumber());
        writer.endArray();
    }
}