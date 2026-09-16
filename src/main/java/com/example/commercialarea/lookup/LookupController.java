package com.example.commercialarea.lookup;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LookupController {

    private final RegionRepository regions;
    private final IndustryRepository industries;

    public LookupController(RegionRepository regions, IndustryRepository industries) {
        this.regions = regions;
        this.industries = industries;
    }

    @GetMapping("/api/regions/sido")
    public List<LookupItem> sido() {
        return regions.findSido();
    }

    @GetMapping("/api/regions/sgg")
    public List<LookupItem> sgg(@RequestParam String sido) {
        return regions.findSgg(sido);
    }

    @GetMapping("/api/regions/dong")
    public List<LookupItem> dong(@RequestParam String sgg) {
        return regions.findDong(sgg);
    }

    @GetMapping("/api/industries/large")
    public List<LookupItem> large() {
        return industries.findLarge();
    }

    @GetMapping("/api/industries/medium")
    public List<LookupItem> medium(@RequestParam String large) {
        return industries.findMedium(large);
    }

    @GetMapping("/api/industries/small")
    public List<LookupItem> small(@RequestParam String medium) {
        return industries.findSmall(medium);
    }
}
