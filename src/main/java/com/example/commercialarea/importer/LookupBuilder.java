package com.example.commercialarea.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LookupBuilder {

    private static final Logger log = LoggerFactory.getLogger(LookupBuilder.class);

    private final JdbcClient jdbc;

    public LookupBuilder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 드롭다운이 122만 행에 SELECT DISTINCT를 돌리지 않도록 룩업 테이블을 만든다.
     * region에 bbox를 함께 담아, 지역 선택 시 해당 구역으로 지도를 이동시킨다.
     */
    @Transactional
    public void rebuild() {
        long started = System.currentTimeMillis();

        jdbc.sql("DELETE FROM region").update();
        jdbc.sql("""
                INSERT INTO region (sido_code, sido_name, sgg_code, sgg_name, dong_code, dong_name,
                                    store_count, min_lat, max_lat, min_lon, max_lon)
                SELECT sido_code, sido_name, sgg_code, sgg_name,
                       COALESCE(dong_code, ''), COALESCE(dong_name, ''),
                       COUNT(*), MIN(lat), MAX(lat), MIN(lon), MAX(lon)
                FROM store
                GROUP BY sido_code, sido_name, sgg_code, sgg_name,
                         COALESCE(dong_code, ''), COALESCE(dong_name, '')
                """).update();

        jdbc.sql("DELETE FROM industry").update();
        jdbc.sql("""
                INSERT INTO industry (large_code, large_name, medium_code, medium_name,
                                      small_code, small_name, store_count)
                SELECT large_code, large_name, medium_code, medium_name,
                       small_code, small_name, COUNT(*)
                FROM store
                GROUP BY large_code, large_name, medium_code, medium_name, small_code, small_name
                """).update();

        log.info("룩업 테이블 재생성 완료 ({}ms)", System.currentTimeMillis() - started);
    }

    /**
     * 룩업 테이블이 채워져 있는지 확인한다. rebuild()는 적재가 모든 대상 파일을 끝까지
     * 처리한 뒤에만 호출되므로, region이 비어 있지 않다는 것은 이전 적재가 완주했다는
     * 신호로 쓸 수 있다. store에 행이 있는데 이 값이 false라면 적재가 중단된 것이다.
     */
    public boolean isPopulated() {
        long count = jdbc.sql("SELECT COUNT(*) FROM region").query(Long.class).single();
        return count > 0;
    }
}
