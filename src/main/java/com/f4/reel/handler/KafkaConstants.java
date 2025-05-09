package com.f4.reel.handler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;

/**
 * Static constants for Kafka configuration retrieved from application
 * properties.
 */
@Component
public class KafkaConstants {
        private static final Logger LOG = LoggerFactory.getLogger(KafkaConstants.class);

        // Bootstrap server configuration
        public static String BOOTSTRAP_SERVERS;

        // Security configuration
        public static String SECURITY_PROTOCOL;
        public static String SSL_KEY_PASSWORD;
        public static String SSL_KEYSTORE_PASSWORD;
        public static String SSL_TRUSTSTORE_PASSWORD;
        public static String SSL_TRUSTSTORE_LOCATION;
        public static String SSL_KEYSTORE_LOCATION;
        public static String SSL_ENDPOINT_IDENTIFICATION_ALGORITHM;

        // Kafka Stream destination
        public static String SERVICE_NAME;
        public static String TOPIC_NAME_OUT;
        public static String TOPIC_NAME_IN;

        // Consumer settings
        public static int CONSUMER_CONCURRENCY;
        public static boolean AUTO_COMMIT_OFFSET;
        public static String START_OFFSET;
        public static boolean ENABLE_DLQ;

        // Producer settings
        public static int PARTITION_COUNT;
        public static boolean SYNC_PRODUCER;
        public static int BUFFER_SIZE;

        private final Environment env;
        private final ResourceLoader resourceLoader;
        private final String bootstrapServers;
        private final String securityProtocol;
        private final String sslKeyPassword;
        private final String sslKeystorePassword;
        private final String sslTruststorePassword;
        private final String sslTruststoreLocation;
        private final String sslKeystoreLocation;
        private final String sslEndpointIdentificationAlgorithm;
        private final String serviceName;

        public KafkaConstants(Environment env,
                        @Value("${spring.cloud.stream.kafka.binder.brokers:appf4.io.vn:9092}") String bootstrapServers,
                        @Value("${spring.cloud.stream.kafka.binder.configuration.security.protocol:SSL}") String securityProtocol,
                        @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.key.password}") String sslKeyPassword,
                        @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.keystore.password}") String sslKeystorePassword,
                        @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.truststore.password}") String sslTruststorePassword,
                        @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.truststore.location}") String sslTruststoreLocation,
                        @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.keystore.location}") String sslKeystoreLocation,
                        @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.endpoint.identification.algorithm:}") String sslEndpointIdentificationAlgorithm,
                        @Value("${ssh.service-name:reel}") String serviceName, ResourceLoader resourceLoader) {

                this.env = env;
                this.resourceLoader = resourceLoader;
                this.bootstrapServers = bootstrapServers;
                this.securityProtocol = securityProtocol;
                this.sslKeyPassword = sslKeyPassword;
                this.sslKeystorePassword = sslKeystorePassword;
                this.sslTruststorePassword = sslTruststorePassword;
                this.sslTruststoreLocation = sslTruststoreLocation;
                this.sslKeystoreLocation = sslKeystoreLocation;
                this.sslEndpointIdentificationAlgorithm = sslEndpointIdentificationAlgorithm;
                this.serviceName = serviceName;
        }

        @PostConstruct
        public void initStaticValues() throws IOException {
                BOOTSTRAP_SERVERS = this.bootstrapServers;
                SECURITY_PROTOCOL = this.securityProtocol;
                SSL_KEY_PASSWORD = this.sslKeyPassword;
                SSL_KEYSTORE_PASSWORD = this.sslKeystorePassword;
                SSL_TRUSTSTORE_PASSWORD = this.sslTruststorePassword;
                SSL_TRUSTSTORE_LOCATION = resolveResourceToFile(this.sslTruststoreLocation).getAbsolutePath();
                SSL_KEYSTORE_LOCATION = resolveResourceToFile(this.sslKeystoreLocation).getAbsolutePath();
                SSL_ENDPOINT_IDENTIFICATION_ALGORITHM = this.sslEndpointIdentificationAlgorithm;
                SERVICE_NAME = this.serviceName;
                TOPIC_NAME_OUT = this.serviceName + "-output";
                TOPIC_NAME_IN = this.serviceName + "-input";
                // Initialize consumer config values from properties
                CONSUMER_CONCURRENCY = env.getProperty(
                                "spring.cloud.stream.kafka.bindings.kafkaConsumer-in-0.consumer.concurrency",
                                Integer.class, 3);
                AUTO_COMMIT_OFFSET = env.getProperty(
                                "spring.cloud.stream.kafka.bindings.kafkaConsumer-in-0.consumer.autoCommitOffset",
                                Boolean.class, true);
                START_OFFSET = env.getProperty(
                                "spring.cloud.stream.kafka.bindings.kafkaConsumer-in-0.consumer.startOffset",
                                "latest");
                ENABLE_DLQ = env.getProperty("spring.cloud.stream.kafka.bindings.kafkaConsumer-in-0.consumer.enableDlq",
                                Boolean.class, true);

                // Initialize producer config values from properties
                PARTITION_COUNT = env.getProperty(
                                "spring.cloud.stream.kafka.bindings.kafkaProducer-out-0.producer.partitionCount",
                                Integer.class, 3);
                SYNC_PRODUCER = env.getProperty("spring.cloud.stream.kafka.bindings.kafkaProducer-out-0.producer.sync",
                                Boolean.class, false);
                BUFFER_SIZE = env.getProperty(
                                "spring.cloud.stream.kafka.bindings.kafkaProducer-out-0.producer.bufferSize",
                                Integer.class, 16384);

                LOG.info("Initialized Kafka static constants for service: {}, topic-in: {}", SERVICE_NAME,
                                TOPIC_NAME_IN);
                LOG.info("Initialized Kafka static constants for service: {}, topic-out: {}", SERVICE_NAME,
                                TOPIC_NAME_OUT);
        }

        private File resolveResourceToFile(String location) throws IOException {
                Resource res = resourceLoader.getResource(location);
                if (res.exists() && res.isFile())
                        return res.getFile();
                File tmp = File.createTempFile("kafka-", ".jks");
                try (InputStream in = res.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = in.read(buf)) > 0)
                                out.write(buf, 0, len);
                }
                tmp.deleteOnExit();
                return tmp;
        }
}
