package com.streamr.client.utils;

import com.streamr.client.exceptions.*;
import com.streamr.client.protocol.message_layer.*;
import com.streamr.client.rest.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A stateful helper class to create StreamMessages, with the following responsibilities:
 *
 * - Maintains message chains by creating appropriate MessageIDs and MessageRefs
 * - Encrypts created messages
 * - Signs created messages
 *
 * Does NOT:
 * - Manage encryption keys
 */
public class MessageCreationUtil {
    private final Address publisherId;
    private final String msgChainId;
    private final SigningUtil signingUtil;

    private final HashMap<String, MessageRef> refsPerStreamAndPartition = new HashMap<>();

    private final HashMap<String, Integer> cachedHashes = new HashMap<>();

    public MessageCreationUtil(Address publisherId, SigningUtil signingUtil) {
        this.publisherId = publisherId;
        msgChainId = RandomStringUtils.randomAlphanumeric(20);
        this.signingUtil = signingUtil;
    }

    public StreamMessage createStreamMessage(Stream stream, Map<String, Object> payload, Date timestamp) {
        return createStreamMessage(stream, payload, timestamp, null, null, null);
    }

    public StreamMessage createStreamMessage(Stream stream, Map<String, Object> payload, Date timestamp, String partitionKey) {
        return createStreamMessage(stream, payload, timestamp, partitionKey, null, null);
    }

    public StreamMessage createStreamMessage(Stream stream, Map<String, Object> payload, Date timestamp, @Nullable String partitionKey, @Nullable GroupKey groupKey, @Nullable GroupKey newGroupKey) {
        int streamPartition = getStreamPartition(stream.getPartitions(), partitionKey);

        Pair<MessageID, MessageRef> pair = createMsgIdAndRef(stream.getId(), streamPartition, timestamp.getTime());
        StreamMessage streamMessage = new StreamMessage(pair.getLeft(), pair.getRight(), payload);

        // Encrypt content if the GroupKey is provided
        if (groupKey != null) {
            try {
                EncryptionUtil.encryptStreamMessage(streamMessage, groupKey);
            } catch (InvalidGroupKeyException e) {
                throw new RuntimeException(e);
            }

            // Encrypt and attach newGroupKey if it's provided
            if (newGroupKey != null) {
                streamMessage.setNewGroupKey(EncryptionUtil.encryptGroupKey(newGroupKey, groupKey));
            }
        }

        // Sign if signingUtil provided
        if (signingUtil != null) {
            signingUtil.signStreamMessage(streamMessage);
        }
        return streamMessage;
    }

    public StreamMessage createGroupKeyRequest(Address publisherAddress, String streamId, String rsaPublicKey, List<String> groupKeyIds) {
        if (signingUtil == null) {
            throw new SigningRequiredException("Cannot create unsigned group key request. Must authenticate with an Ethereum account");
        }

        GroupKeyRequest request = new GroupKeyRequest(UUID.randomUUID().toString(), streamId, rsaPublicKey, groupKeyIds);

        String keyExchangeStreamId = KeyExchangeUtil.getKeyExchangeStreamId(publisherAddress);
        Pair<MessageID, MessageRef> pair = createDefaultMsgIdAndRef(keyExchangeStreamId);
        StreamMessage streamMessage = request.toStreamMessage(pair.getLeft(), pair.getRight());

        // Never encrypt but always sign
        signingUtil.signStreamMessage(streamMessage);
        return streamMessage;
    }

    public StreamMessage createGroupKeyResponse(Address subscriberAddress, GroupKeyRequest request, List<GroupKey> groupKeys) {
        if (signingUtil == null) {
            throw new SigningRequiredException("Cannot create unsigned group key response. Must authenticate with an Ethereum account");
        }

        // Encrypt the group keys
        List<EncryptedGroupKey> encryptedGroupKeys = groupKeys.stream().map(key -> {
                RSAPublicKey publicKey = EncryptionUtil.getPublicKeyFromString(request.getPublicKey());
                return EncryptionUtil.encryptWithPublicKey(key, publicKey);
            }
        ).collect(Collectors.toList());

        GroupKeyResponse response = new GroupKeyResponse(
                request.getRequestId(),
                request.getStreamId(),
                encryptedGroupKeys
        );

        String keyExchangeStreamId = KeyExchangeUtil.getKeyExchangeStreamId(subscriberAddress);
        Pair<MessageID, MessageRef> pair = createDefaultMsgIdAndRef(keyExchangeStreamId);
        StreamMessage streamMessage = response.toStreamMessage(pair.getLeft(), pair.getRight());
        streamMessage.setEncryptionType(StreamMessage.EncryptionType.RSA);
        streamMessage.setGroupKeyId(request.getPublicKey());

        // Always sign
        signingUtil.signStreamMessage(streamMessage);
        return streamMessage;
    }

    public StreamMessage createGroupKeyAnnounce(Address subscriberAddress, String streamId, String publicKey, List<GroupKey> groupKeys) {
        if (signingUtil == null) {
            throw new SigningRequiredException("Cannot create unsigned group key announce. Must authenticate with an Ethereum account");
        }

        // Encrypt the group keys
        List<EncryptedGroupKey> encryptedGroupKeys = groupKeys.stream().map(key -> {
                    RSAPublicKey rsaPublicKey = EncryptionUtil.getPublicKeyFromString(publicKey);
                    return EncryptionUtil.encryptWithPublicKey(key, rsaPublicKey);
                }
        ).collect(Collectors.toList());

        GroupKeyAnnounce announce = new GroupKeyAnnounce(streamId, encryptedGroupKeys);

        String keyExchangeStreamId = KeyExchangeUtil.getKeyExchangeStreamId(subscriberAddress);
        Pair<MessageID, MessageRef> pair = createDefaultMsgIdAndRef(keyExchangeStreamId);
        StreamMessage streamMessage = announce.toStreamMessage(pair.getLeft(), pair.getRight());
        streamMessage.setEncryptionType(StreamMessage.EncryptionType.RSA);
        streamMessage.setGroupKeyId(publicKey);

        // Always sign
        signingUtil.signStreamMessage(streamMessage);
        return streamMessage;
    }

    public StreamMessage createGroupKeyErrorResponse(Address destinationAddress, GroupKeyRequest request, Exception e) {
        if (signingUtil == null) {
            throw new SigningRequiredException("Cannot create unsigned error message. Must authenticate with an Ethereum account");
        }

        GroupKeyErrorResponse response = new GroupKeyErrorResponse(
                request.getRequestId(),
                request.getStreamId(),
                getErrorCodeFromException(e),
                e.getMessage(),
                request.getGroupKeyIds()
        );

        String keyExchangeStreamId = KeyExchangeUtil.getKeyExchangeStreamId(destinationAddress);
        Pair<MessageID, MessageRef> pair = createDefaultMsgIdAndRef(keyExchangeStreamId);
        StreamMessage streamMessage = response.toStreamMessage(pair.getLeft(), pair.getRight());

        // Never encrypt but always sign
        signingUtil.signStreamMessage(streamMessage);
        return streamMessage;
    }

    private String getErrorCodeFromException(Exception e) {
        if (e instanceof InvalidGroupKeyRequestException) {
            return "INVALID_GROUP_KEY_REQUEST";
        } else if (e instanceof InvalidGroupKeyResponseException) {
            return "INVALID_GROUP_KEY_RESPONSE";
        } else if (e instanceof MalformedMessageException) {
            return "INVALID_CONTENT_TYPE";
        } else {
            return "UNEXPECTED_ERROR";
        }
    }

    private int hash(String partitionKey) {
        Integer hash = cachedHashes.get(partitionKey);
        if (hash == null) {
            byte[] bytes;
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                bytes = md.digest(partitionKey.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            hash = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            cachedHashes.put(partitionKey, hash);
        }
        return hash;
    }

    private int getStreamPartition(int nbPartitions, String partitionKey) {
        if (nbPartitions == 0) {
            throw new Error("partitionCount is zero!");
        } else if (nbPartitions == 1) {
            return 0;
        } else if (partitionKey != null) {
            int h = hash(partitionKey);
            return Math.abs(h) % nbPartitions;
        } else {
            return (int) Math.floor(Math.random() * nbPartitions);
        }
    }

    private Pair<MessageID, MessageRef> createMsgIdAndRef(String streamId, int streamPartition, long timestamp) {
        String key = streamId + streamPartition;
        long sequenceNumber = getNextSequenceNumber(key, timestamp);
        MessageID msgId = new MessageID(streamId, streamPartition, timestamp, sequenceNumber, publisherId, msgChainId);
        MessageRef prevMsgRef = refsPerStreamAndPartition.get(key);
        Pair<MessageID, MessageRef> p = Pair.of(msgId, prevMsgRef);
        refsPerStreamAndPartition.put(key, new MessageRef(timestamp, sequenceNumber));
        return p;
    }

    private Pair<MessageID, MessageRef> createDefaultMsgIdAndRef(String streamId) {
        return createMsgIdAndRef(streamId, 0, (new Date()).getTime());
    }

    private long getNextSequenceNumber(String key, long timestamp) {
        MessageRef prev = refsPerStreamAndPartition.get(key);
        if (prev == null || prev.getTimestamp() != timestamp) {
            return 0L;
        }
        return prev.getSequenceNumber() + 1L;
    }
}
