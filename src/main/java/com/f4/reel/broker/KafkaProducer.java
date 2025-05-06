package com.f4.reel.broker;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.f4.reel.service.dto.ReelDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class KafkaProducer implements Supplier<String> {

    private final StreamBridge streamBridge;
    private final ObjectMapper mapper;

    public KafkaProducer(StreamBridge streamBridge, ObjectMapper mapper) {
        this.streamBridge = streamBridge;
        this.mapper = mapper;
    }

    // super naive “random word” generator just for demo
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
        dto.setCreatedAt(Instant.now().minusSeconds((long) (Math.random() * 3_600)));
        Map<String, Object> event = Map.of(
                "eventName", "postReel",
                "payload", Map.<String, Object>of(
                        "userId", dto.getUserId(),
                        "title", dto.getTitle(),
                        "videoUrl", dto.getVideoUrl()));
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String get() {
        int lastPartition = (int) (Math.random() * 9) + 2; // Randomly select partition 2 to 10
        for (int i = 0; i < lastPartition; i++) {
            streamBridge.send("reel-output", buildFakeDto());
            streamBridge.send("reel-output", buildFakeDto());
            streamBridge.send("reel-output", buildFakeDto());
            streamBridge.send("reel-output", buildFakeDto());
            streamBridge.send("reel-output", buildFakeDto());
            streamBridge.send("reel-output", buildFakeDto());

        }

        return buildFakeDto(); // Optional, if you want to return something
    }
}
