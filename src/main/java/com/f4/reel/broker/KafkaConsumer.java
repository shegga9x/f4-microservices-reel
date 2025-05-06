package com.f4.reel.broker;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.f4.reel.handler.EventDispatcher;
import com.f4.reel.handler.EventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

@Component
public class KafkaConsumer implements Consumer<String> {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumer.class);

    private final EventDispatcher dispatcher;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final TaskExecutor taskExecutor;

    public KafkaConsumer(EventDispatcher dispatcher, TaskExecutor taskExecutor) {
        this.dispatcher = dispatcher;
        this.taskExecutor = taskExecutor;
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
    public void accept(String rawJson) {
        ThreadLocal<Long> startTime = ThreadLocal.withInitial(System::currentTimeMillis); // Start time tracking

        CompletableFuture
                .supplyAsync(() -> parseEnvelope(rawJson),
                        CompletableFuture.delayedExecutor(0, java.util.concurrent.TimeUnit.MILLISECONDS, taskExecutor))
                .thenCompose(env -> dispatchAndBroadcast(env, rawJson))
                .thenAccept(v -> {
                    long endTime = System.currentTimeMillis(); // End time tracking
                    LOG.debug("✅ Successfully handled event in {} ms", (endTime - startTime.get()));
                    startTime.remove(); // Clean up thread-local variable
                })
                .exceptionally(ex -> {
                    long endTime = System.currentTimeMillis(); // End time tracking
                    LOG.error("❌ Failed to process event in {} ms", (endTime - startTime.get()), ex);
                    startTime.remove(); // Clean up thread-local variable
                    return null;
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
