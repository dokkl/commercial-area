package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisQueryTest {

    private static AnalysisQuery at(double minLat, double maxLat, double minLon, double maxLon) {
        return new AnalysisQuery(minLat, maxLat, minLon, maxLon,
                null, null, null, null, null, null, null);
    }

    @Test
    void 정상_bbox는_통과한다() {
        assertThatCode(() -> at(37.0, 38.0, 126.0, 127.0).validate()).doesNotThrowAnyException();
    }

    @Test
    void 위도가_뒤집히면_INVALID_BBOX를_던진다() {
        assertThatThrownBy(() -> at(38.0, 37.0, 126.0, 127.0).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_BBOX");
    }

    @Test
    void 경도가_뒤집히면_INVALID_BBOX를_던진다() {
        assertThatThrownBy(() -> at(37.0, 38.0, 127.0, 126.0).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_BBOX");
    }
}
