package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapQueryTest {

    private static MapQuery at(double minLat, double maxLat, double minLon, double maxLon, int zoom) {
        return new MapQuery(minLat, maxLat, minLon, maxLon, zoom,
                null, null, null, null, null, null, null);
    }

    @Test
    void 격자_크기는_줌이_1_커질_때마다_절반이_된다() {
        double z10 = at(37.0, 38.0, 126.0, 127.0, 10).cellSize();
        double z11 = at(37.0, 38.0, 126.0, 127.0, 11).cellSize();

        assertThat(z11).isCloseTo(z10 / 2, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void 격자_크기는_전역_원점_기준_공식을_따른다() {
        assertThat(at(37.0, 38.0, 126.0, 127.0, 0).cellSize()).isEqualTo(45.0);   // 360/8
        assertThat(at(37.0, 38.0, 126.0, 127.0, 3).cellSize()).isEqualTo(5.625);  // 360/64
    }

    @Test
    void 정상_bbox는_통과한다() {
        assertThatCode(() -> at(37.0, 38.0, 126.0, 127.0, 11).validate()).doesNotThrowAnyException();
    }

    @Test
    void 위도가_뒤집히면_INVALID_BBOX를_던진다() {
        assertThatThrownBy(() -> at(38.0, 37.0, 126.0, 127.0, 11).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_BBOX");
    }

    @Test
    void 경도가_뒤집히면_INVALID_BBOX를_던진다() {
        assertThatThrownBy(() -> at(37.0, 38.0, 127.0, 126.0, 11).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_BBOX");
    }

    @Test
    void 줌이_범위_밖이면_INVALID_ZOOM을_던진다() {
        assertThatThrownBy(() -> at(37.0, 38.0, 126.0, 127.0, 23).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ZOOM");
        assertThatThrownBy(() -> at(37.0, 38.0, 126.0, 127.0, -1).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ZOOM");
    }
}
