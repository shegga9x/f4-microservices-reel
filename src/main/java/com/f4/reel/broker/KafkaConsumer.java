package com.f4.reel.broker;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.f4.reel.handler.EventDispatcher;
import com.f4.reel.handler.EventEnvelope;
import com.f4.reel.handler.KafkaConstants;
import com.f4.reel.handler.KafkaStoreService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.Message;
import jakarta.annotation.PreDestroy;

@Component
public class KafkaConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumer.class);

    private final EventDispatcher dispatcher;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final TaskExecutor taskExecutor;
    private final KafkaProducer kafkaProducer;
    private final KafkaStoreService messageStoreService;

    public KafkaConsumer(EventDispatcher dispatcher, TaskExecutor taskExecutor,
            KafkaProducer kafkaProducer, KafkaStoreService messageStoreService) {
        this.dispatcher = dispatcher;
        this.taskExecutor = taskExecutor;
        this.kafkaProducer = kafkaProducer;
        this.messageStoreService = messageStoreService;
    }

    public SseEmitter register(String key) {
        LOG.debug("Registering sse client for {}", key);
        SseEmitter emitter = new SseEmitter();
        emitter.onCompletion(() -> emitters.remove(key));
        emitters.put(key, emitter);
        return emitter;
    }

    public void unregister(String key) {
        LOG.debug("Unregistering sse emitter for: {}", key);
        Optional.ofNullable(emitters.remove(key)).ifPresent(SseEmitter::complete);
    }

    @PreDestroy
    public void shutdown() {
        // No need to shutdown taskExecutor as it is managed by Spring
    }

    @KafkaListener(topics = "${ssh.service-name:reel}-input", groupId = "${kafka.consumer.group-id:reel-group}", containerFactory = "kafkaListenerContainerFactory")
    public void processMessage(
            @Payload String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = "correlationId", required = false) String correlationIdHeader,
            @Header(name = KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(name = KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt,
            Message<String> message,
            Acknowledgment acknowledgment) {

        ThreadLocal<Long> startTime = ThreadLocal.withInitial(System::currentTimeMillis); // Start time tracking

        // First try our custom header
        final String correlationId = Optional.ofNullable(correlationIdHeader)
                .orElse(key);

        // Log retry attempts if present
        if (deliveryAttempt != null && deliveryAttempt > 1) {
            LOG.info("Processing retry attempt {} for message with correlation ID: {}",
                    deliveryAttempt, correlationId);
        }

        // Generate a new correlation ID if none exists
        final String finalCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        // Store the incoming message with its correlation ID
        messageStoreService.storeMessage(finalCorrelationId, message, "INCOMING", KafkaConstants.TOPIC_NAME_IN);

        String rawJson = payload;
        // … now use correlationId in your success / error callbacks …
        try {
            CompletableFuture
                    .supplyAsync(() -> parseEnvelope(rawJson),
                            CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS,
                                    taskExecutor))
                    .thenCompose(env -> dispatchAndBroadcast(env, rawJson))
                    .thenAccept(v -> {
                        long end = System.currentTimeMillis();
                        LOG.debug("✅ Success in {}ms", end - startTime.get());
                        startTime.remove();
                        kafkaProducer.sendSuccessStatus(finalCorrelationId,
                                Map.of("processingTimeMs", end - startTime.get()));
                        // Acknowledge successful processing
                        acknowledgment.acknowledge();
                    })
                    .exceptionally(ex -> {
                        long end = System.currentTimeMillis();

                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

                        if (cause instanceof RetryableException) {
                            LOG.warn("⚠️ Retryable error in {}ms. This will trigger a retry: {}",
                                    end - startTime.get(), cause.getMessage());
                            // Don't acknowledge - let the container retry
                            throw new RuntimeException("Retryable error: " + cause.getMessage(), cause);
                        } else {
                            LOG.error("❌ Non-retryable error in {}ms", end - startTime.get(), ex);
                            startTime.remove();
                            kafkaProducer.sendErrorStatus(
                                    finalCorrelationId,
                                    ex.getMessage(),
                                    Map.of("processingTimeMs", end - startTime.get()));
                            // Acknowledge to prevent endless retries for non-retryable errors
                            acknowledgment.acknowledge();
                        }
                        return null; // swallow
                    }).get(); // Block to ensure completion - we want to handle exceptions synchronously
        } catch (Exception e) {
            // If we have a RetryableException, don't ack so the message will be redelivered
            if (shouldRetry(e)) {
                LOG.warn("Encountered retryable exception, not acknowledging message to trigger retry", e);
                // Let the exception propagate to trigger retry
                throw new RuntimeException("Retryable exception encountered", e);
            } else {
                // For other exceptions, ack to prevent endless redelivery
                LOG.error("Encountered non-retryable exception, acknowledging message", e);
                acknowledgment.acknowledge();
            }
        }
    }

    private EventEnvelope<JsonNode> parseEnvelope(String rawJson) {
        EventEnvelope<JsonNode> env = null;
        try {
            env = mapper.readValue(
                    rawJson,
                    new TypeReference<EventEnvelope<JsonNode>>() {
                    });
            return env;
        } catch (JsonProcessingException e) {
            throw new CompletionException("Invalid JSON envelope", e);
        }
    }

    private CompletableFuture<Void> dispatchAndBroadcast(EventEnvelope<JsonNode> env, String rawJson) {
        return CompletableFuture.runAsync(() -> {
            // your business logic
            try {
                dispatcher.dispatch(env);
            } catch (Exception e) {
                // Determine if this exception should trigger a retry
                if (shouldRetry(e)) {
                    LOG.warn("Error dispatching event - will retry", e);
                    throw new RetryableException("Error dispatching event", e);
                } else {
                    LOG.error("Error dispatching event - will not retry", e);
                    throw new RuntimeException("Error dispatching event", e);
                }
            }
            // cleanup any dead SSE clients
            emitters.values().removeIf(em -> !sendSse(em, env));
        }, CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS, taskExecutor));
    }

    /**
     * Determines if an exception should trigger a retry.
     *
     * @param exception The exception to check
     * @return true if the exception should trigger a retry, false otherwise
     */
    private boolean shouldRetry(Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof RetryableException ||
                    cause instanceof java.net.SocketTimeoutException ||
                    cause instanceof java.io.IOException ||
                    cause instanceof java.sql.SQLException ||
                    cause instanceof org.hibernate.StaleObjectStateException ||
                    (cause.getMessage() != null && (cause.getMessage().toLowerCase().contains("timeout") ||
                            cause.getMessage().toLowerCase().contains("connection refused")))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private boolean sendSse(SseEmitter emitter, EventEnvelope<JsonNode> env) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(env.getEventName()) // you can send the event name too
                            .data(env.getPayload().toString(), MediaType.APPLICATION_JSON));

            return true;
        } catch (IOException | IllegalStateException ex) {
            LOG.debug("Failed to send to SSE client, removing emitter", ex);
            return false;
        }
    }
}
