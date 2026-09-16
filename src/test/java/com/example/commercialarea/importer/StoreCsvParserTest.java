package com.example.commercialarea.importer;

import com.example.commercialarea.store.Store;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreCsvParserTest {

    private final StoreCsvParser parser = new StoreCsvParser();

    private List<CSVRecord> records() throws Exception {
        try (Reader reader = new InputStreamReader(
                getClass().getResourceAsStream("/fixtures/sample-stores.csv"), StandardCharsets.UTF_8);
             CSVParser csv = new CSVParser(reader, StoreCsvParser.FORMAT)) {
            return new ArrayList<>(csv.getRecords());
        }
    }

    @Test
    void 헤더_다음_첫_행의_모든_필드를_매핑한다() throws Exception {
        Store s = parser.parse(records().get(0));

        assertThat(s.storeId()).isNotBlank();
        assertThat(s.storeName()).isNotBlank();
        assertThat(s.largeCode()).isNotBlank();
        assertThat(s.largeName()).isNotBlank();
        assertThat(s.sidoName()).isEqualTo("서울특별시");
        assertThat(s.sggName()).isNotBlank();
        assertThat(s.lat()).isBetween(37.0, 38.0);
        assertThat(s.lon()).isBetween(126.0, 128.0);
    }

    @Test
    void 상호명에_콤마가_있어도_한_필드로_파싱한다() throws Exception {
        Store s = findById(records(), "TEST0000000000000000001");

        assertThat(s.storeName()).isEqualTo("가나다, 라마바");
        assertThat(s.lat()).isEqualTo(37.4979);
        assertThat(s.lon()).isEqualTo(127.0276);
        assertThat(s.floorInfo()).isEqualTo("2");
        assertThat(s.buildingName()).isEqualTo("테스트빌딩");
    }

    @Test
    void 빈_문자열_필드는_null로_변환한다() throws Exception {
        Store s = findById(records(), "TEST0000000000000000003");

        assertThat(s.branchName()).isNull();
        assertThat(s.dongCode()).isNull();
        assertThat(s.dongName()).isNull();
        assertThat(s.buildingName()).isNull();
        assertThat(s.floorInfo()).isNull();
    }

    @Test
    void 좌표가_비어_있으면_MalformedRowException을_던진다() throws Exception {
        CSVRecord broken = records().stream()
                .filter(r -> r.get("상가업소번호").equals("TEST0000000000000000002"))
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> parser.parse(broken))
                .isInstanceOf(MalformedRowException.class)
                .hasMessageContaining("좌표");
    }

    @Test
    void 헤더_이름으로_매핑하므로_컬럼이_39개다() throws Exception {
        assertThat(records().get(0).getParser().getHeaderNames()).hasSize(39);
    }

    private Store findById(List<CSVRecord> records, String id) {
        return records.stream()
                .filter(r -> r.get("상가업소번호").equals(id))
                .map(parser::parse)
                .findFirst().orElseThrow();
    }
}
