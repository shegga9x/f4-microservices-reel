package com.f4.reel.handler;

import org.apache.kafka.clients.admin.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import jakarta.annotation.PostConstruct;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

@Configuration
public class KafkaTopicConfig {
    private final Environment env;
    private final ResourceLoader resourceLoader;
    private final String sslKeyPassword;
    private final String sslKeystorePassword;
    private final String sslTruststorePassword;
    private final String sslTruststoreLocation;
    private final String sslKeystoreLocation;
    private final String sslEndpointIdentificationAlgorithm;

    public KafkaTopicConfig(Environment env,
            ResourceLoader resourceLoader,
            @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.key.password}") String sslKeyPassword,
            @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.keystore.password}") String sslKeystorePassword,
            @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.truststore.password}") String sslTruststorePassword,
            @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.truststore.location}") String sslTruststoreLocation,
            @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.keystore.location}") String sslKeystoreLocation,
            @Value("${spring.cloud.stream.kafka.binder.configuration.ssl.endpoint.identification.algorithm}") String sslEndpointIdentificationAlgorithm) {
        this.env = env;
        this.resourceLoader = resourceLoader;
        this.sslKeyPassword = sslKeyPassword;
        this.sslKeystorePassword = sslKeystorePassword;
        this.sslTruststorePassword = sslTruststorePassword;
        this.sslTruststoreLocation = sslTruststoreLocation;
        this.sslKeystoreLocation = sslKeystoreLocation;
        this.sslEndpointIdentificationAlgorithm = sslEndpointIdentificationAlgorithm;
    }

    @SuppressWarnings("deprecation")
    @PostConstruct
    public void ensureTopic() throws IOException {
        String bs = env.getProperty("spring.stream.cloud.kafka.binder.brokers", "appf4.io.vn:9092");
        String topic = "reel-output";
        int partitions = 3;
        short repl = 1;
        Properties cfg = new Properties();
        cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bs);
        cfg.put("security.protocol", "SSL");
        cfg.put("ssl.endpoint.identification.algorithm", sslEndpointIdentificationAlgorithm);
        cfg.put("ssl.keystore.location", resolveResourceToFile(sslKeystoreLocation).getAbsolutePath());
        cfg.put("ssl.keystore.password", sslKeystorePassword);
        cfg.put("ssl.key.password", sslKeyPassword);
        cfg.put("ssl.truststore.location", resolveResourceToFile(sslTruststoreLocation).getAbsolutePath());
        cfg.put("ssl.truststore.password", sslTruststorePassword);
        try (AdminClient admin = AdminClient.create(cfg)) {
            Set<String> names = admin.listTopics().names().get();
            if (!names.contains(topic)) {
                admin.createTopics(Collections.singleton(new NewTopic(topic, partitions, repl))).all().get();
            } else if (admin.describeTopics(Collections.singleton(topic)).all().get().get(topic).partitions()
                    .size() != partitions) {
                admin.createPartitions(Collections.singletonMap(topic, NewPartitions.increaseTo(partitions))).all()
                        .get();
            }
        } catch (Exception e) {
            System.err.println("[WARN] Kafka topic setup: " + e.getMessage());
        }
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
