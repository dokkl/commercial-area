package com.example.commercialarea.lookup;

import com.fasterxml.jackson.annotation.JsonInclude;

/** bbox는 지역 항목에만 담긴다. 업종에는 null이라 JSON에서 생략된다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LookupItem(
        String code, String name, long count,
        Double minLat, Double maxLat, Double minLon, Double maxLon
) {
    public static LookupItem of(String code, String name, long count) {
        return new LookupItem(code, name, count, null, null, null, null);
    }
}
