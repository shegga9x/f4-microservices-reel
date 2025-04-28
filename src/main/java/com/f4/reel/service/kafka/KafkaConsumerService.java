package com.f4.reel.service.kafka;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@KafkaListener(topics = "reel-info-topic", groupId = "reel-dto")
public class KafkaConsumerService {

    @KafkaHandler
    public void consume(String message) {
        System.out.println("Received Message: " + message);
    }
}
