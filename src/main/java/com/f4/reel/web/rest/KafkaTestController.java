package com.f4.reel.web.rest;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.f4.reel.kafka.broker.KafkaProducer;

@RestController
@RequestMapping("/api/kafka-test")
public class KafkaTestController {

    private static final Logger log = LoggerFactory.getLogger(KafkaTestController.class);
    private final KafkaProducer kafkaProducer;

    public KafkaTestController(KafkaProducer kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    public static class ReelRequest {
        private String userId;
        private String title;
        private String videoUrl;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getVideoUrl() {
            return videoUrl;
        }

        public void setVideoUrl(String videoUrl) {
            this.videoUrl = videoUrl;
        }

        @Override
        public String toString() {
            return "ReelRequest [userId=" + userId + ", title=" + title + ", videoUrl=" + videoUrl + "]";
        }
    }

    @PostMapping("/send-reel-direct")
    public ResponseEntity<String> sendReelDirectly(@RequestBody ReelRequest request) {
        log.info("REST request to prepare and DIRECTLY SEND Reel via Kafka: {}", request);

        try {
            kafkaProducer.send(
                    UUID.fromString(request.getUserId()),
                    request.getTitle(),
                    request.getVideoUrl());

            return ResponseEntity.ok(
                    "Reel event preparation and direct send attempt initiated via KafkaProducer. Check logs for StreamBridge status from KafkaUtilityService.");
        } catch (Exception e) {
            log.error("Failed to prepare and attempt direct send for reel event via KafkaProducer", e);
            return ResponseEntity.badRequest()
                    .body("Failed during direct send attempt via KafkaProducer: " + e.getMessage());
        }
    }
}