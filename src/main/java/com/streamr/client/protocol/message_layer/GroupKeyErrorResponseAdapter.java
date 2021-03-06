package com.streamr.client.protocol.message_layer;

import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.streamr.client.utils.HttpUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;

public class GroupKeyErrorResponseAdapter extends AbstractGroupKeyMessageAdapter<GroupKeyErrorResponse> {

    @Nullable
    @Override
    public GroupKeyErrorResponse fromJson(JsonReader reader) throws IOException {
        reader.beginArray();
        String requestId = reader.nextString();
        String streamId = reader.nextString();
        String errorCode = reader.nextString();
        String errorMessage = reader.nextString();
        List<String> groupKeyIds = HttpUtils.listAdapter.fromJson(reader);
        reader.endArray();

        return new GroupKeyErrorResponse(requestId, streamId, errorCode, errorMessage, groupKeyIds);
    }

    @Override
    public void toJson(JsonWriter writer, @Nullable GroupKeyErrorResponse message) throws IOException {
        writer.beginArray();
        writer.value(message.getRequestId());
        writer.value(message.getStreamId());
        writer.value(message.getCode());
        writer.value(message.getMessage());
        HttpUtils.listAdapter.toJson(writer, message.getGroupKeyIds());
        writer.endArray();
    }

}
