package com.f4.reel.broker;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.f4.reel.handler.KafkaConstants;
import com.f4.reel.handler.KafkaStoreService;
import com.f4.reel.service.dto.ReelDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.Message;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class KafkaProducer implements Supplier<Message<String>> {
    // public class KafkaProducer {
    private final StreamBridge streamBridge;
    private final ObjectMapper mapper;
    private final KafkaStoreService messageStoreService;

    private static final DateTimeFormatter INSTANT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    public KafkaProducer(StreamBridge streamBridge, ObjectMapper mapper, KafkaStoreService messageStoreService) {
        this.streamBridge = streamBridge;
        this.mapper = mapper;
        this.messageStoreService = messageStoreService;
    }

    // super naive "random word" generator just for demo
    private String randomWord() {
        String[] words = { "Sunset", "Epic", "Chill", "Vibes", "Adventure", "Mystery" };
        return words[(int) (Math.random() * words.length)];
    }

    private String buildFakeDto() {
        ReelDTO dto = new ReelDTO();
        dto.setVersion(1L);
        dto.setId(UUID.randomUUID());
        dto.setUserId(UUID.randomUUID());
        dto.setTitle("🔥 Reel #" + " – " + randomWord());
        dto.setVideoUrl("https://cdn.example.com/videos/fake-video-" + ".mp4");

        // Create an Instant for the current time minus a random offset
        Instant now = Instant.now().minusSeconds((long) (Math.random() * 3_600));
        dto.setCreatedAt(now);

        // Create a mutable map for the payload
        Map<String, Object> payload = new HashMap<>();
        // payload.put("id", dto.getId());
        payload.put("userId", dto.getUserId());
        payload.put("title", dto.getTitle());
        payload.put("videoUrl", dto.getVideoUrl());
        // Format the Instant as a string using the formatter that matches the expected
        // pattern
        payload.put("createdAt", INSTANT_FORMATTER.format(now));
        payload.put("version", dto.getVersion());

        Map<String, Object> event = Map.of(
                "eventName", "postReel",
                "payload", payload);

        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Message<String> get() {
        int lastPartition = (int) (Math.random() * 9) + 2; // Randomly select
        // partition 2 to 10
        for (int i = 0; i < lastPartition; i++) {
        sendOne(KafkaConstants.TOPIC_NAME_IN, buildFakeDto());
        sendOne(KafkaConstants.TOPIC_NAME_IN, buildFakeDto());
        sendOne(KafkaConstants.TOPIC_NAME_IN, buildFakeDto());
        sendOne(KafkaConstants.TOPIC_NAME_IN, buildFakeDto());
        sendOne(KafkaConstants.TOPIC_NAME_IN, buildFakeDto());
        sendOne(KafkaConstants.TOPIC_NAME_IN, buildFakeDto());
        }

        String correlationId = UUID.randomUUID().toString();
        return MessageBuilder
                .withPayload(
                        buildFakeDto())
                .setHeader(KafkaHeaders.KEY, correlationId.getBytes()) // Convert string key to byte[]
                .setHeader("correlationId", correlationId) // also as a custom header
                .build(); // Optional, if you want to return something
    }

    public String sendOne(String destionation, String rawJson) {
        String correlationId = UUID.randomUUID().toString();

        Message<String> msg = MessageBuilder
                .withPayload(
                        rawJson)
                .setHeader(KafkaHeaders.KEY, correlationId.getBytes()) // Convert string key to byte[]
                .setHeader("correlationId", correlationId) // also as a custom header
                .build();

        streamBridge.send(destionation, msg);

        return correlationId;
    }

    /**
     * Sends a success status notification to the status topic
     * 
     * @param messageId The ID of the original message
     * @param details   Additional details about the successful processing
     */
    public void sendSuccessStatus(String messageId, Map<String, Object> details) {
        Map<String, Object> statusEvent = buildStatusEvent("SUCCESS", details, null);
        sendStatus(statusEvent, messageId);
    }

    /**
     * Sends an error status notification to the status topic
     * 
     * @param messageId    The ID of the original message
     * @param errorMessage The error message
     * @param errorDetails Additional details about the error
     */
    public void sendErrorStatus(String messageId, String errorMessage, Map<String, Object> errorDetails) {
        Map<String, Object> details = new HashMap<>();
        if (errorDetails != null) {
            details.putAll(errorDetails);
        }
        details.put("errorMessage", errorMessage);

        Map<String, Object> statusEvent = buildStatusEvent("ERROR", details, errorMessage);
        sendStatus(statusEvent, messageId);
    }

    private Map<String, Object> buildStatusEvent(String status, Map<String, Object> details,
            String errorMessage) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status);
        payload.put("timestamp", INSTANT_FORMATTER.format(Instant.now()));
        payload.put("serviceName", KafkaConstants.SERVICE_NAME);

        if (details != null) {
            payload.put("details", details);
        }

        if (errorMessage != null) {
            payload.put("errorMessage", errorMessage);
        }

        Map<String, Object> event = Map.of(
                "eventName", "messageStatus",
                "payload", payload);

        return event;
    }

    private void sendStatus(Map<String, Object> statusEvent, String correlationId) {
        try {
            String statusJson = mapper.writeValueAsString(statusEvent);

            // Use a default UUID if correlationId is null
            String safeCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();

            Message<String> msg = MessageBuilder
                    .withPayload(statusJson)
                    // set the Kafka record key (optional, but useful for partitioning)
                    .setHeader(KafkaHeaders.KEY, safeCorrelationId.getBytes()) // Convert string key to byte[]
                    // also set a custom header for easy retrieval
                    .setHeader("correlationId", safeCorrelationId)
                    .setHeader("contentType", "application/json")
                    .build();
            messageStoreService.storeMessage(correlationId, msg, "OUTCOMING", KafkaConstants.TOPIC_NAME_IN);
            // BINDING_NAME must match your spring.cloud.stream.binding.<n>.destination
            messageStoreService.findMessagesByCorrelationId(correlationId).forEach(System.out::println);
            streamBridge.send(KafkaConstants.TOPIC_NAME_OUT, msg);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to send status notification", e);
        }
    }

}
