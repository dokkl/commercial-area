package com.example.commercialarea.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MapController {

    private final StoreService service;

    public MapController(StoreService service) {
        this.service = service;
    }

    @GetMapping("/api/map")
    public MapResponse map(
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
            @RequestParam(required = false) String q) {

        MapQuery query = new MapQuery(minLat, maxLat, minLon, maxLon, zoom,
                sido, sgg, dong, large, medium, small, q);
        query.validate();
        return service.map(query);
    }
}
