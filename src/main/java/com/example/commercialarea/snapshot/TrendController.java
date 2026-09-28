package com.example.commercialarea.snapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrendController {

    private final TrendService service;

    public TrendController(TrendService service) {
        this.service = service;
    }

    @GetMapping("/api/trend")
    public TrendResponse trend(
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sgg,
            @RequestParam(required = false) String dong,
            @RequestParam(required = false) String large,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String small) {

        return service.trend(new TrendQuery(sido, sgg, dong, large, medium, small));
    }
}
