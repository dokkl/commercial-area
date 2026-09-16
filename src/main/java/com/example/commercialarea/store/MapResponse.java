package com.example.commercialarea.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapResponse(String mode, long total, List<GridCell> cells, List<MapPoint> points) {

    public static MapResponse cluster(long total, List<GridCell> cells) {
        return new MapResponse("cluster", total, cells, null);
    }

    public static MapResponse point(long total, List<MapPoint> points) {
        return new MapResponse("point", total, null, points);
    }
}
