package com.example.commercialarea.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @GetMapping("/api/analysis")
    public AnalysisResponse analyze(
            @RequestParam double minLat,
            @RequestParam double maxLat,
            @RequestParam double minLon,
            @RequestParam double maxLon,
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sgg,
            @RequestParam(required = false) String dong,
            @RequestParam(required = false) String large,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String small,
            @RequestParam(required = false) String q) {

        AnalysisQuery query = new AnalysisQuery(minLat, maxLat, minLon, maxLon,
                sido, sgg, dong, large, medium, small, q);
        query.validate();
        return service.analyze(query);
    }
}
