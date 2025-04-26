package com.f4.reel.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.f4.reel.service.dto.ReelDTO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ReelConsumer {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ReelService reelService;

    private Logger log = LoggerFactory.getLogger(ReelConsumer.class);

    @KafkaListener(topics = "reel-info-topic", groupId = "reel-info-group")
    public void consume(String reelId) {
        try {
            // Gọi API
            String url = "https://appf4.io.vn/services/reel-dev/api/reels/" + reelId;
            ResponseEntity<ReelDTO> response = restTemplate.getForEntity(url, ReelDTO.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                ReelDTO reel = response.getBody();

                // Lưu Redis: key = "reel:{id}", value = ReelDTO
                // redisTemplate.opsForValue().set("reel:" + reelId, reel);
                reelService.cacheReel(reel);
                log.info("✅ Reel cached: {}", reelId);
            } else {
                log.info("⚠️ Failed to fetch reel:  {}", reelId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
