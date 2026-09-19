package com.example.commercialarea.store;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoAreaTest {

    @Test
    void 알려진_bbox의_면적을_근사한다() {
        // 0.1도 x 0.1도, 위도 약 37도. 등장방형 근사로 약 98.2 km².
        double km2 = GeoArea.km2(37.0, 37.1, 127.0, 127.1);
        assertThat(km2).isCloseTo(98.2, Offset.offset(1.0));
    }

    @Test
    void 넓이가_0인_bbox는_0을_반환한다() {
        assertThat(GeoArea.km2(37.0, 37.0, 127.0, 127.1)).isZero();
        assertThat(GeoArea.km2(37.0, 37.1, 127.0, 127.0)).isZero();
    }

    @Test
    void 역전된_bbox는_0을_반환한다() {
        assertThat(GeoArea.km2(37.1, 37.0, 127.0, 127.1)).isZero();
    }
}