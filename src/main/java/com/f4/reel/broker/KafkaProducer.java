package com.f4.reel.broker;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaProducer implements Supplier<String> {

    @Override
    public String get() {
        return "kafka_producer";
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // 1. Gửi message (Producer)
    public void requestReelInfo(String reelID) {
        kafkaTemplate.send("reel-info-topic", reelID);
    }
}
