package com.f4.reel.web.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.f4.reel.service.PexelsService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pexels")
public class PexelsController {

    private final PexelsService pexelsService;

    public PexelsController(PexelsService pexelsService) {
        this.pexelsService = pexelsService;
    }

    @GetMapping("/search")
    public ResponseEntity<List<String>> searchImages(
        @RequestParam String query,
        @RequestParam(defaultValue = "10") int perPage
    ) {
        List<String> imageUrls = pexelsService.searchImages(query, perPage);
        return ResponseEntity.ok(imageUrls);
    }

    @PostMapping("/download")
    public ResponseEntity<String> downloadAndStoreImage(@RequestParam String imageUrl) {
        String storedUrl = pexelsService.downloadAndStoreImage(imageUrl);
        return ResponseEntity.ok(storedUrl);
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file) {
        String storedUrl = pexelsService.uploadImageToMinio(file);
        return ResponseEntity.ok(storedUrl);
    }

    @PostMapping("/store")
    public ResponseEntity<String> storePexelsImage(@RequestParam String pexelsImageUrl) {
        String storedUrl = pexelsService.storePexelsImage(pexelsImageUrl);
        return ResponseEntity.ok(storedUrl);
    }
    
    // New video endpoints
    @GetMapping("/videos/search")
    public ResponseEntity<List<Map<String, Object>>> searchVideos(
        @RequestParam String query,
        @RequestParam(defaultValue = "10") int perPage
    ) {
        List<Map<String, Object>> videoDetails = pexelsService.searchVideos(query, perPage);
        return ResponseEntity.ok(videoDetails);
    }
    
    @PostMapping("/videos/download")
    public ResponseEntity<String> downloadAndStoreVideo(@RequestParam String videoUrl) {
        String storedUrl = pexelsService.downloadAndStoreVideo(videoUrl);
        return ResponseEntity.ok(storedUrl);
    }
    
    @PostMapping("/videos/upload")
    public ResponseEntity<String> uploadVideo(@RequestParam("file") MultipartFile file) {
        String storedUrl = pexelsService.uploadVideoToMinio(file);
        return ResponseEntity.ok(storedUrl);
    }
    
    @PostMapping("/videos/store")
    public ResponseEntity<String> storePexelsVideo(@RequestParam String pexelsVideoUrl) {
        String storedUrl = pexelsService.storePexelsVideo(pexelsVideoUrl);
        return ResponseEntity.ok(storedUrl);
    }
} 