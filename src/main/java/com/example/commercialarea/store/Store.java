package com.example.commercialarea.store;

public record Store(
        String storeId,
        String storeName,
        String branchName,
        String largeCode,
        String largeName,
        String mediumCode,
        String mediumName,
        String smallCode,
        String smallName,
        String sidoCode,
        String sidoName,
        String sggCode,
        String sggName,
        String dongCode,
        String dongName,
        String lotAddress,
        String buildingName,
        String roadAddress,
        String floorInfo,
        double lon,
        double lat
) {
}
