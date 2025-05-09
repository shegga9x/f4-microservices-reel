package com.f4.reel.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.KafkaHeaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for Kafka and retry functionality.
 * This class sets up Kafka consumers and producers with retry capabilities.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConfig.class);
    
    private final ResourceLoader resourceLoader;

    @Value("${spring.cloud.stream.kafka.binder.brokers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.cloud.stream.kafka.binder.configuration.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.endpoint.identification.algorithm:}")
    private String sslEndpointIdentificationAlgorithm;
    
    @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.truststore.location:classpath:config/tls/kafka.truststore.jks}")
    private String sslTruststoreLocation;

    @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.truststore.password:f4security}")
    private String sslTruststorePassword;
    
    @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.keystore.location:classpath:config/tls/kafka.keystore.jks}")
    private String sslKeystoreLocation;

    @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.keystore.password:f4security}")
    private String sslKeystorePassword;

    @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.key.password:f4security}")
    private String sslKeyPassword;

    @Value("${kafka.consumer.max-attempts:5}")
    private int maxAttempts;

    @Value("${kafka.consumer.backoff-initial-interval:1000}")
    private long initialInterval;

    @Value("${kafka.consumer.backoff-max-interval:10000}")
    private long maxInterval;

    @Value("${kafka.consumer.backoff-multiplier:2.0}")
    private double multiplier;

    @Value("${kafka.consumer.concurrency:3}")
    private int concurrency;

    @Value("${kafka.consumer.group-id:reel-group}")
    private String groupId;
    
    public KafkaConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Creates the Kafka consumer factory with the appropriate configuration.
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        // Must be false to enable manual acks
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Add SSL configuration if using SSL
        try {
            if ("SSL".equalsIgnoreCase(securityProtocol)) {
                props.put("security.protocol", "SSL");
                props.put("ssl.endpoint.identification.algorithm", sslEndpointIdentificationAlgorithm);
                
                // Use the resourceLoader to resolve the file paths
                File trustStoreFile = resolveResourceToFile(sslTruststoreLocation);
                File keyStoreFile = resolveResourceToFile(sslKeystoreLocation);
                
                props.put("ssl.truststore.location", trustStoreFile.getAbsolutePath());
                props.put("ssl.truststore.password", sslTruststorePassword);
                props.put("ssl.keystore.location", keyStoreFile.getAbsolutePath());
                props.put("ssl.keystore.password", sslKeystorePassword);
                props.put("ssl.key.password", sslKeyPassword);
            }
        } catch (Exception e) {
            LOG.warn("Error configuring SSL for Kafka consumer: {}", e.getMessage(), e);
            // Continue without SSL configuration
        }

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Creates and configures the Kafka listener container factory with retry
     * capabilities.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(concurrency);

        // MANUAL means the listener is responsible for acknowledging messages
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // Configure retry with exponential backoff
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxAttempts - 1);
        backOff.setInitialInterval(initialInterval);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxInterval);

        // Configure Dead Letter Topic (DLT) for messages that fail after all retries
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    String topic = record.topic() + ".DLT";
                    LOG.error("Failed to process message after {} attempts. Sending to DLT topic: {}. Error: {}",
                            maxAttempts, topic, ex.getMessage());
                    return new org.apache.kafka.common.TopicPartition(topic, record.partition());
                });

        // Create error handler with retry capability
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Skip retries for exceptions that aren't retryable
        errorHandler.addNotRetryableExceptions(
                org.springframework.messaging.converter.MessageConversionException.class,
                org.springframework.validation.BindException.class,
                jakarta.validation.ValidationException.class,
                IllegalArgumentException.class,
                java.lang.IllegalStateException.class);

        // Log retry attempts
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
            LOG.warn("Retry attempt {} for record with key {} on topic {}: {}",
                    deliveryAttempt, record.key(), record.topic(), ex.getMessage());
        });

        factory.setCommonErrorHandler(errorHandler);

        // Add record interceptor to send successful retried messages to DLT
        factory.setRecordInterceptor(new RecordInterceptor<String, String>() {
            @Override
            public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                    Consumer<String, String> consumer) {
                // Get the delivery attempt header set by Spring Kafka
                Integer deliveryAttempt = null;
                if (record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT) != null) {
                    byte[] headerValue = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT).value();
                    if (headerValue != null) {
                        deliveryAttempt = Integer.valueOf(new String(headerValue));
                    }
                }

                // If this is a retry (delivery attempt > 1), send to DLT with retry info
                if (deliveryAttempt != null && deliveryAttempt > 1) {
                    String topic = record.topic() + ".DLT";
                    String newKey = record.key() + ".DLT";
                    
                    ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                        topic,
                        record.partition(), 
                        record.timestamp(),
                        newKey,
                        record.value()
                    );
                    
                    // Copy all original headers
                    record.headers().forEach(header -> 
                        producerRecord.headers().add(header)
                    );
                    
                    // Add retry count header
                    producerRecord.headers().add("x-retry-count", String.valueOf(deliveryAttempt - 1).getBytes());
                    producerRecord.headers().add("x-status", "SUCCESS_AFTER_RETRY".getBytes());
                    
                    LOG.info("Successfully processed message after {} retries. Sending copy to DLT topic: {}", 
                            deliveryAttempt - 1, topic);
                            
                    kafkaTemplate.send(producerRecord);
                }
                
                return record;
            }
        });

        return factory;
    }

    /**
     * Creates the Kafka producer factory with the appropriate configuration.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Add reliability config
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Add SSL configuration if using SSL
        try {
            if ("SSL".equalsIgnoreCase(securityProtocol)) {
                configProps.put("security.protocol", "SSL");
                configProps.put("ssl.endpoint.identification.algorithm", sslEndpointIdentificationAlgorithm);
                
                // Use the resourceLoader to resolve the file paths
                File trustStoreFile = resolveResourceToFile(sslTruststoreLocation);
                File keyStoreFile = resolveResourceToFile(sslKeystoreLocation);
                
                configProps.put("ssl.truststore.location", trustStoreFile.getAbsolutePath());
                configProps.put("ssl.truststore.password", sslTruststorePassword);
                configProps.put("ssl.keystore.location", keyStoreFile.getAbsolutePath());
                configProps.put("ssl.keystore.password", sslKeystorePassword);
                configProps.put("ssl.key.password", sslKeyPassword);
            }
        } catch (Exception e) {
            LOG.warn("Error configuring SSL for Kafka producer: {}", e.getMessage(), e);
            // Continue without SSL configuration
        }

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Creates the Kafka template for sending messages.
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
    
    /**
     * Resolves a resource location into a temporary file if needed.
     * 
     * @param location the resource location
     * @return the resolved file
     * @throws IOException if there's an error resolving the resource
     */
    private File resolveResourceToFile(String location) throws IOException {
        Resource res = resourceLoader.getResource(location);
        if (res.exists() && res.isFile()) {
            return res.getFile();
        }
        File tmp = File.createTempFile("kafka-", ".jks");
        try (InputStream in = res.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        tmp.deleteOnExit();
        return tmp;
    }
}