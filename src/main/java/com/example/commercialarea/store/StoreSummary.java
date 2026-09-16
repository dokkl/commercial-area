package com.example.commercialarea.store;

public record StoreSummary(
        String id, String name, String branchName,
        String largeName, String mediumName, String smallName,
        String roadAddress, double lat, double lon
) {
}
