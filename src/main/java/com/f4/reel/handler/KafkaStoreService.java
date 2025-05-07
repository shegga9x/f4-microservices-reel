package com.f4.reel.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Service for storing and retrieving Kafka messages by correlationId.
 * Useful for tracking and debugging message flows.
 */
@Service
public class KafkaStoreService {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaStoreService.class);

    // Maximum number of messages to store per correlationId
    private static final int MAX_MESSAGES_PER_ID = 10;

    // Maximum number of correlationIds to track
    private static final int MAX_CORRELATION_IDS = 1000;

    // Store messages by correlationId
    private final Map<String, List<StoredMessage>> messageStore = new ConcurrentHashMap<>();

    /**
     * Store a message with its correlationId
     * 
     * @param correlationId the correlationId associated with the message
     * @param message       the message to store
     * @param direction     "INCOMING" or "OUTGOING"
     * @param topic         the Kafka topic
     */
    public void storeMessage(String correlationId, Message<?> message, String direction, String topic) {
        if (correlationId == null) {
            LOG.debug("Cannot store message without correlationId");
            return;
        }

        // Prune if we're tracking too many correlationIds
        if (messageStore.size() > MAX_CORRELATION_IDS && !messageStore.containsKey(correlationId)) {
            // Remove a random entry to prevent growing unbounded
            Optional.ofNullable(messageStore.keySet().stream().findFirst().orElse(null))
                    .ifPresent(messageStore::remove);
        }

        // Create or get the list for this correlationId
        List<StoredMessage> messages = messageStore.computeIfAbsent(correlationId, k -> new ArrayList<>());

        // Add new message
        StoredMessage storedMessage = new StoredMessage(
                System.currentTimeMillis(),
                direction,
                topic,
                message.getPayload().toString(),
                message.getHeaders());

        synchronized (messages) {
            messages.add(storedMessage);

            // Prune if we're storing too many messages per correlationId
            if (messages.size() > MAX_MESSAGES_PER_ID) {
                messages.remove(0);
            }
        }

        LOG.debug("Stored {} message with correlationId: {}", direction, correlationId);
    }

    /**
     * Find messages by correlationId
     * 
     * @param correlationId the correlationId to search for
     * @return list of stored messages, or empty list if none found
     */
    public List<StoredMessage> findMessagesByCorrelationId(String correlationId) {
        if (correlationId == null) {
            return Collections.emptyList();
        }

        return messageStore.getOrDefault(correlationId, Collections.emptyList());
    }

    /**
     * Get all tracked correlationIds
     * 
     * @return list of all correlationIds being tracked
     */
    public List<String> getAllCorrelationIds() {
        return new ArrayList<>(messageStore.keySet());
    }

    /**
     * Clear all stored messages for a specific correlationId
     * 
     * @param correlationId the correlationId to clear
     */
    public void clearMessages(String correlationId) {
        messageStore.remove(correlationId);
    }

    /**
     * Clear all stored messages
     */
    public void clearAllMessages() {
        messageStore.clear();
    }

    /**
     * Inner class to represent a stored message
     */
    public static class StoredMessage {
        private final long timestamp;
        private final String direction;
        private final String topic;
        private final String payload;
        private final Map<String, Object> headers;

        public StoredMessage(long timestamp, String direction, String topic, String payload,
                Map<String, Object> headers) {
            this.timestamp = timestamp;
            this.direction = direction;
            this.topic = topic;
            this.payload = payload;
            this.headers = headers;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getDirection() {
            return direction;
        }

        public String getTopic() {
            return topic;
        }

        public String getPayload() {
            return payload;
        }

        public Map<String, Object> getHeaders() {
            return headers;
        }

        @Override
        public String toString() {
            return "StoredMessage [headers=" + headers + "]";
        }

    }
}
