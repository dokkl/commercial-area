package com.example.commercialarea.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StoreController {

    private static final int MAX_PAGE_SIZE = 100;

    private final StoreService service;

    public StoreController(StoreService service) {
        this.service = service;
    }

    @GetMapping("/api/stores")
    public PageResponse<StoreSummary> list(
            @RequestParam double minLat,
            @RequestParam double maxLat,
            @RequestParam double minLon,
            @RequestParam double maxLon,
            @RequestParam int zoom,
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sgg,
            @RequestParam(required = false) String dong,
            @RequestParam(required = false) String large,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String small,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        MapQuery query = new MapQuery(minLat, maxLat, minLon, maxLon, zoom,
                sido, sgg, dong, large, medium, small, q);
        query.validate();
        return service.list(query, Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/api/stores/{id}")
    public StoreDetail detail(@PathVariable String id) {
        return service.detail(id);
    }
}
