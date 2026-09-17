package com.example.commercialarea.lookup;

import com.example.commercialarea.importer.LookupBuilder;
import com.example.commercialarea.store.Store;
import com.example.commercialarea.store.StoreRepository;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.enabled=false")
@AutoConfigureMockMvc
@Import(MySqlTestContainer.class)
class LookupApiTest {

    @Autowired MockMvc mvc;
    @Autowired StoreRepository storeRepository;
    @Autowired LookupBuilder lookupBuilder;
    @Autowired JdbcClient jdbc;

    private static Store store(String id, String sggCode, String sggName,
                               String dongCode, String dongName,
                               String large, String largeName,
                               String medium, String mediumName,
                               String small, String smallName,
                               double lat, double lon) {
        return new Store(id, "가게" + id, null,
                large, largeName, medium, mediumName, small, smallName,
                "11", "서울특별시", sggCode, sggName, dongCode, dongName,
                "지번", null, "도로명", null, lon, lat);
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        storeRepository.insertBatch(List.of(
                store("R1", "11680", "강남구", "11680510", "역삼1동",
                        "I2", "음식", "I201", "한식", "I20101", "백반·가정식", 37.50, 127.02),
                store("R2", "11680", "강남구", "11680510", "역삼1동",
                        "I2", "음식", "I201", "한식", "I20102", "국수·면요리", 37.51, 127.03),
                store("R3", "11680", "강남구", "11680520", "역삼2동",
                        "G2", "소매", "G205", "식료품 소매", "G20501", "슈퍼마켓", 37.52, 127.04),
                store("R4", "11110", "종로구", "11110515", "사직동",
                        "I2", "음식", "I202", "중식", "I20201", "중식 일반", 37.57, 126.97),
                // R1과 (large,medium,small) triple이 완전히 같다 — 다른 지역(11110)에 위치시켜
                // region 레벨 검증(11680, 11680510 대상)에는 영향을 주지 않으면서, industry
                // 테이블의 해당 행만 store_count=2로 만든다. 이 triple이 유일하면 세 업종
                // 레벨 쿼리 모두 SUM(store_count)와 COUNT(*)가 우연히 같아져 어느 쪽으로
                // 구현해도 테스트가 통과해 버린다(실제로 있었던 문제).
                store("R5", "11110", "종로구", "11110600", "청운효자동",
                        "I2", "음식", "I201", "한식", "I20101", "백반·가정식", 37.58, 126.96)
        ));
        lookupBuilder.rebuild();
    }

    @Test
    void 시도_목록은_건수를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/sido"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("11"))
                .andExpect(jsonPath("$[0].name").value("서울특별시"))
                .andExpect(jsonPath("$[0].count").value(5));
    }

    @Test
    void 시군구_목록은_행정동_행을_집계하고_bbox를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/sgg?sido=11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680')].count").value(3))
                .andExpect(jsonPath("$[?(@.code=='11680')].minLat").value(37.50))
                .andExpect(jsonPath("$[?(@.code=='11680')].maxLat").value(37.52))
                .andExpect(jsonPath("$[?(@.code=='11680')].minLon").value(127.02))
                .andExpect(jsonPath("$[?(@.code=='11680')].maxLon").value(127.04));
    }

    @Test
    void 행정동_목록도_bbox를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/dong?sgg=11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680510')].count").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680510')].maxLat").value(37.51));
    }

    @Test
    void 업종_대분류는_중복_없이_집계된다() throws Exception {
        mvc.perform(get("/api/industries/large"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // I2 아래 (large,medium,small) triple은 3개(I20101,I20102,I20201)뿐이지만
                // I20101의 store_count가 2(R1,R5)라 SUM은 4다. COUNT(*)였다면 3이 나와
                // 이 단언이 깨진다 — SUM을 실제로 쓰는지 증명한다.
                .andExpect(jsonPath("$[?(@.code=='I2')].count").value(4))
                .andExpect(jsonPath("$[?(@.code=='G2')].count").value(1));
    }

    @Test
    void 업종_중분류는_대분류로_좁혀진다() throws Exception {
        mvc.perform(get("/api/industries/medium?large=I2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // I201 아래 triple은 2개(I20101,I20102)지만 I20101의 store_count가 2라
                // SUM은 3이다. COUNT(*)였다면 2가 나와 이 단언이 깨진다.
                .andExpect(jsonPath("$[?(@.code=='I201')].count").value(3));
    }

    @Test
    void 업종_소분류는_중분류로_좁혀진다() throws Exception {
        mvc.perform(get("/api/industries/small?medium=I201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='I20101')].name").value("백반·가정식"))
                // I20101 자체가 industry의 한 행이라 COUNT(*)는 늘 1이다. store_count가
                // 2(R1,R5)인 것을 SUM(store_count)로 실제로 반환하는지 여기서 확인한다.
                .andExpect(jsonPath("$[?(@.code=='I20101')].count").value(2));
    }

    @Test
    void 업종_목록에는_bbox가_없다() throws Exception {
        mvc.perform(get("/api/industries/large"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].minLat").doesNotExist());
    }

    /**
     * 매핑되지 않은 URL은 NoResourceFoundException을 던지는데, 이를 캐치올이 잡으면
     * 500/INTERNAL_ERROR로 잘못 분류된다. 클라이언트 오타 하나가 서버 오류 경보로
     * 둔갑하지 않도록 404/NOT_FOUND로 응답하는지 확인한다.
     */
    @Test
    void 매핑되지_않은_경로는_404_NOT_FOUND이다() throws Exception {
        mvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    /**
     * bbox 4개(minLat/maxLat/minLon/maxLon)가 아닌 필수 파라미터가 빠지면 MISSING_BBOX가
     * 아니라 MISSING_PARAMETER로 응답해야 한다 (GlobalExceptionHandler#handleMissingParam).
     */
    @Test
    void bbox가_아닌_필수_파라미터가_없으면_MISSING_PARAMETER이다() throws Exception {
        mvc.perform(get("/api/regions/sgg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_PARAMETER"));
    }
}
