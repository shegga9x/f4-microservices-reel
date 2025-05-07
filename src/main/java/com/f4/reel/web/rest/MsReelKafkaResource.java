package com.f4.reel.web.rest;

import com.f4.reel.broker.KafkaConsumer;
import com.f4.reel.handler.EventEnvelope;
import com.f4.reel.handler.KafkaStoreService;
import com.f4.reel.handler.KafkaStoreService.StoredMessage;
import com.fasterxml.jackson.databind.JsonNode;

import java.security.Principal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@RestController
@RequestMapping("/api/ms-reel-kafka")
public class MsReelKafkaResource {

    private static final String PRODUCER_BINDING_NAME = "binding-out-0";

    private static final Logger LOG = LoggerFactory.getLogger(MsReelKafkaResource.class);
    private final KafkaConsumer kafkaConsumer;
    private final StreamBridge streamBridge;
    private final KafkaStoreService messageStoreService;

    public MsReelKafkaResource(StreamBridge streamBridge, KafkaConsumer kafkaConsumer,
            KafkaStoreService messageStoreService) {
        this.streamBridge = streamBridge;
        this.kafkaConsumer = kafkaConsumer;
        this.messageStoreService = messageStoreService;
    }

    @PostMapping("/publish")
    public ResponseEntity<Void> publish(
            @RequestParam("event") String eventName,
            @RequestBody JsonNode payload) {
        EventEnvelope<JsonNode> env = new EventEnvelope<>(eventName, payload);
        boolean sent = streamBridge.send(PRODUCER_BINDING_NAME, env);
        return sent
                ? ResponseEntity.accepted().build()
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @GetMapping("/register")
    public ResponseBodyEmitter register(Principal principal) {
        return kafkaConsumer.register(principal.getName());
    }

    @GetMapping("/unregister")
    public void unregister(Principal principal) {
        kafkaConsumer.unregister(principal.getName());
    }

    /**
     * Get all tracked correlation IDs.
     * 
     * @return List of all correlation IDs currently being tracked
     */
    @GetMapping("/correlationIds")
    public ResponseEntity<List<String>> getAllCorrelationIds() {
        List<String> ids = messageStoreService.getAllCorrelationIds();
        return ResponseEntity.ok(ids);
    }

    /**
     * Find all messages by correlation ID.
     * 
     * @param correlationId The correlation ID to search for
     * @return List of messages with the given correlation ID
     */
    @GetMapping("/messages/{correlationId}")
    public ResponseEntity<List<StoredMessage>> findMessagesByCorrelationId(
            @PathVariable String correlationId) {
        List<StoredMessage> messages = messageStoreService.findMessagesByCorrelationId(correlationId);
        if (messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(messages);
    }

    /**
     * Clear stored messages for a specific correlation ID.
     * 
     * @param correlationId The correlation ID to clear
     * @return No content response
     */
    @DeleteMapping("/messages/{correlationId}")
    public ResponseEntity<Void> clearMessages(@PathVariable String correlationId) {
        messageStoreService.clearMessages(correlationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clear all stored messages.
     * 
     * @return No content response
     */
    @DeleteMapping("/messages")
    public ResponseEntity<Void> clearAllMessages() {
        messageStoreService.clearAllMessages();
        return ResponseEntity.noContent().build();
    }
}
