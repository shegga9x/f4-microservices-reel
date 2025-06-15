package com.f4.reel.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.f4.reel.service.PexelsService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PexelsServiceImpl implements PexelsService {

    @Value("${pexels.api-key}")
    private String apiKey;

    @Value("${pexels.base-url}")
    private String baseUrl;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    @Value("${minio.access-key}")
    private String minioAccessKey;

    @Value("${minio.secret-key}")
    private String minioSecretKey;

    @Value("${minio.bucket-name}")
    private String bucketName;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PexelsServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> searchImages(String query, int perPage) {
        List<String> imageUrls = new ArrayList<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", apiKey);

            String url = String.format("%s/search?query=%s&per_page=%d", baseUrl, query, perPage);
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode photos = root.path("photos");

            for (JsonNode photo : photos) {
                imageUrls.add(photo.path("src").path("original").asText());
            }
        } catch (Exception e) {
            throw new RuntimeException("Error searching Pexels images", e);
        }
        return imageUrls;
    }

    @Override
    public String downloadAndStoreImage(String imageUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", apiKey);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    imageUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    byte[].class);

            String fileName = UUID.randomUUID().toString() + ".jpg";

            MinioClient minioClient = MinioClient.builder()
                    .endpoint(minioEndpoint)
                    .credentials(minioAccessKey, minioSecretKey)
                    .build();

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(fileName)
                            .stream(new java.io.ByteArrayInputStream(response.getBody()), response.getBody().length, -1)
                            .contentType("image/jpeg")
                            .build());

            return minioEndpoint + "/" + bucketName + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Error downloading and storing image", e);
        }
    }

    @Override
    public String uploadImageToMinio(MultipartFile file) {
        try {
            String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();

            MinioClient minioClient = MinioClient.builder()
                    .endpoint(minioEndpoint)
                    .credentials(minioAccessKey, minioSecretKey)
                    .build();

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(fileName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());

            return minioEndpoint + "/" + bucketName + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Error uploading image to MinIO", e);
        }
    }

    @Override
    public String storePexelsImage(String pexelsImageUrl) {
        try {
            // Extract photo ID from URL
            Pattern pattern = Pattern.compile("/photos/(\\d+)/");
            Matcher matcher = pattern.matcher(pexelsImageUrl);
            if (!matcher.find()) {
                throw new RuntimeException("Invalid Pexels image URL format");
            }
            String photoId = matcher.group(1);

            // Get photo details from Pexels API
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", apiKey);

            // Add a browser-like User-Agent to bypass Cloudflare
            headers.set("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

            String apiUrl = baseUrl + "/photos/" + photoId;
            ResponseEntity<String> photoResponse = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);

            JsonNode photoData = objectMapper.readTree(photoResponse.getBody());
            String originalImageUrl = photoData.path("src").path("original").asText();

            // Download and store the image
            ResponseEntity<byte[]> imageResponse = restTemplate.exchange(
                    originalImageUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    byte[].class);

            String fileName = "pexels-" + photoId + "-" + UUID.randomUUID().toString() + ".jpg";

            MinioClient minioClient = MinioClient.builder()
                    .endpoint(minioEndpoint)
                    .credentials(minioAccessKey, minioSecretKey)
                    .build();

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(fileName)
                            .stream(new java.io.ByteArrayInputStream(imageResponse.getBody()),
                                    imageResponse.getBody().length, -1)
                            .contentType("image/jpeg")
                            .build());

            return minioEndpoint + "/" + bucketName + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Error storing Pexels image: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> searchVideos(String query, int perPage) {
        List<Map<String, Object>> videoDetails = new ArrayList<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", apiKey);
            headers.set("User-Agent", 
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            String url = String.format("%s/videos/search?query=%s&per_page=%d", baseUrl, query, perPage);
            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode videos = root.path("videos");
            
            for (JsonNode video : videos) {
                Map<String, Object> videoDetail = new HashMap<>();
                videoDetail.put("id", video.path("id").asText());
                videoDetail.put("width", video.path("width").asInt());
                videoDetail.put("height", video.path("height").asInt());
                videoDetail.put("duration", video.path("duration").asInt());
                videoDetail.put("url", video.path("url").asText());
                
                Map<String, String> videoFiles = new HashMap<>();
                JsonNode files = video.path("video_files");
                for (JsonNode file : files) {
                    String quality = file.path("quality").asText();
                    videoFiles.put(quality, file.path("link").asText());
                }
                videoDetail.put("files", videoFiles);
                
                Map<String, String> videoPictures = new HashMap<>();
                JsonNode pictures = video.path("video_pictures");
                for (int i = 0; i < pictures.size(); i++) {
                    videoPictures.put("picture_" + i, pictures.path(i).path("picture").asText());
                }
                videoDetail.put("pictures", videoPictures);
                
                videoDetails.add(videoDetail);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error searching Pexels videos", e);
        }
        return videoDetails;
    }

    @Override
    public String downloadAndStoreVideo(String videoUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", 
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            ResponseEntity<byte[]> response = restTemplate.exchange(
                videoUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);

            String fileName = UUID.randomUUID().toString() + ".mp4";
            
            MinioClient minioClient = MinioClient.builder()
                .endpoint(minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();

            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(new java.io.ByteArrayInputStream(response.getBody()), response.getBody().length, -1)
                    .contentType("video/mp4")
                    .build());

            return minioEndpoint + "/" + bucketName + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Error downloading and storing video", e);
        }
    }

    @Override
    public String uploadVideoToMinio(MultipartFile file) {
        try {
            String fileName = "video-" + UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            
            MinioClient minioClient = MinioClient.builder()
                .endpoint(minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();

            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());

            return minioEndpoint + "/" + bucketName + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Error uploading video to MinIO", e);
        }
    }
    
    @Override
    public String storePexelsVideo(String pexelsVideoUrl) {
        try {
            // Extract the video ID and quality from the URL
            // Format: https://videos.pexels.com/video-files/5212084/5212084-hd_1920_1080_25fps.mp4
            String videoId = "unknown";
            String quality = "unknown";
            
            // Parse the URL to extract video ID and quality
            Pattern pattern = Pattern.compile("/video-files/(\\d+)/(\\d+)-(.+)\\.mp4");
            Matcher matcher = pattern.matcher(pexelsVideoUrl);
            if (matcher.find()) {
                videoId = matcher.group(1);
                quality = matcher.group(3); // e.g., "hd_1920_1080_25fps"
            }
            
            // Set up headers for download
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", 
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            // Download the video
            ResponseEntity<byte[]> videoResponse = restTemplate.exchange(
                    pexelsVideoUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    byte[].class);
            
            // Create a filename that includes video ID and quality
            String fileName = String.format("pexels-video-%s-%s-%s.mp4", 
                    videoId, quality, UUID.randomUUID().toString().substring(0, 8));
            
            // Upload to MinIO
            MinioClient minioClient = MinioClient.builder()
                .endpoint(minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();
            
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileName)
                    .stream(new java.io.ByteArrayInputStream(videoResponse.getBody()),
                            videoResponse.getBody().length, -1)
                    .contentType("video/mp4")
                    .build());
            
            return minioEndpoint + "/" + bucketName + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("Error storing Pexels video: " + e.getMessage(), e);
        }
    }
}