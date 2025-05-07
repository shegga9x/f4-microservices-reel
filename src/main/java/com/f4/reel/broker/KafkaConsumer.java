package com.f4.reel.broker;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.KafkaHeaders;
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
public class KafkaConsumer implements Consumer<Message<String>> {

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

    @Override
    public void accept(Message<String> message) {
        ThreadLocal<Long> startTime = ThreadLocal.withInitial(System::currentTimeMillis); // Start time tracking

        // First try our custom header
        final String correlationId = Optional.ofNullable(message.getHeaders().get("correlationId", String.class))
                .orElse(message.getHeaders().get(KafkaHeaders.KEY, String.class));

        // Generate a new correlation ID if none exists
        final String finalCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        // Store the incoming message with its correlation ID
        messageStoreService.storeMessage(finalCorrelationId, message, "INCOMING", KafkaConstants.TOPIC_NAME_IN);

        String rawJson = message.getPayload();
        // … now use correlationId in your success / error callbacks …
        CompletableFuture
                .supplyAsync(() -> parseEnvelope(rawJson),
                        CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS, taskExecutor))
                .thenCompose(env -> dispatchAndBroadcast(env, rawJson))
                .thenAccept(v -> {
                    long end = System.currentTimeMillis();
                    LOG.debug("✅ Success in {}ms", end - startTime.get());
                    startTime.remove();
                    kafkaProducer.sendSuccessStatus(finalCorrelationId,
                            Map.of("processingTimeMs", end - startTime.get()));
                })
                .exceptionally(ex -> {
                    long end = System.currentTimeMillis();
                    LOG.error("❌ Error in {}ms", end - startTime.get(), ex);
                    startTime.remove();
                    kafkaProducer.sendErrorStatus(
                            finalCorrelationId,
                            ex.getMessage(),
                            Map.of("processingTimeMs", end - startTime.get()));
                    return null; // swallow
                });
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
                LOG.error("Error dispatching event", e);
                throw new RuntimeException("Error dispatching event", e);
            }
            // cleanup any dead SSE clients
            emitters.values().removeIf(em -> !sendSse(em, env));
        }, CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS, taskExecutor));
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
