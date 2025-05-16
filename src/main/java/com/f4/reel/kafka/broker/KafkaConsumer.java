package com.f4.reel.kafka.broker;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.f4.reel.avro.EventEnvelope;
import com.f4.reel.kafka.service.KafkaUtilityService;

@Component
public class KafkaConsumer implements Consumer<EventEnvelope> {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumer.class);

    private final KafkaUtilityService kafkaUtilityService;

    public KafkaConsumer(KafkaUtilityService kafkaUtilityService) {
        this.kafkaUtilityService = kafkaUtilityService;
    }

    @Override
    public void accept(EventEnvelope avroMessage) {
        if (avroMessage == null) {
            LOG.error("Received null Avro message - possible deserialization issue");
            return;
        }

        LOG.debug("Got Avro message from kafka stream: {}", avroMessage);

        kafkaUtilityService.submitEventJob(avroMessage);
    }

    public SseEmitter register(String key) {
        return kafkaUtilityService.consumer_registerSseEmitter(key, kafkaUtilityService.getEmitters());
    }

    public void unregister(String key) {
        kafkaUtilityService.consumer_unregisterSseEmitter(key, kafkaUtilityService.getEmitters());
    }
}
