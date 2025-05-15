package com.f4.reel.web.rest;

import com.f4.reel.service.ReelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing Elasticsearch index administration operations.
 */
@RestController
@RequestMapping("/api/admin/elasticsearch")
public class ElasticsearchAdminResource {

    private final Logger log = LoggerFactory.getLogger(ElasticsearchAdminResource.class);

    private final ReelService reelService;

    public ElasticsearchAdminResource(ReelService reelService) {
        this.reelService = reelService;
    }

    /**
     * POST /reindex/reels : Reindex all reels in Elasticsearch
     *
     * @return the ResponseEntity with status 200 (OK) or with status 500 (Internal Server Error)
     */
    @PostMapping("/reindex/reels")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Void> reindexReels() {
        log.info("REST request to reindex all reels in Elasticsearch");
        try {
            reelService.reindexAll();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to reindex all reels", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}