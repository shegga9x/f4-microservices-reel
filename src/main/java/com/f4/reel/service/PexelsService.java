package com.f4.reel.service;

import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

public interface PexelsService {
    List<String> searchImages(String query, int perPage);
    String downloadAndStoreImage(String imageUrl);
    String uploadImageToMinio(MultipartFile file);
    String storePexelsImage(String pexelsImageUrl);
    
    // New video methods
    List<Map<String, Object>> searchVideos(String query, int perPage);
    String downloadAndStoreVideo(String videoUrl);
    String uploadVideoToMinio(MultipartFile file);
    String storePexelsVideo(String pexelsVideoUrl);
} 