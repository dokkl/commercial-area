package com.example.commercialarea.config;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportPropertiesTest {

    @Test
    void include가_비어있으면_모든_파일을_허용한다() {
        ImportProperties props = new ImportProperties(true, "/tmp", List.of(), 1000, "202606");

        assertThat(props.matches("아무_파일.csv")).isTrue();
    }

    @Test
    void include_패턴을_포함하는_파일명은_허용한다() {
        ImportProperties props = new ImportProperties(true, "/tmp", List.of("서울", "경기"), 1000, "202606");

        assertThat(props.matches("소상공인_서울_202606.csv")).isTrue();
        assertThat(props.matches("소상공인_제주_202606.csv")).isFalse();
    }

    /**
     * macOS에서 압축 해제된 CSV 파일명은 한글이 NFD(자모 분해) 형태로 저장되곤 한다.
     * include 값은 NFC(완성형)이므로, 정규화 없이 String.contains만 쓰면 실제 파일과
     * 매칭되지 않는 실제 운영 버그가 있었다. 이 테스트는 그 회귀를 막는다.
     */
    @Test
    void NFD로_분해된_파일명도_NFC_include_패턴과_매칭된다() {
        ImportProperties props = new ImportProperties(true, "/tmp", List.of("서울"), 1000, "202606");

        String nfdFileName = Normalizer.normalize("소상공인_서울_202606.csv", Normalizer.Form.NFD);

        assertThat(props.matches(nfdFileName)).isTrue();
    }

    @Test
    void snapshot이_6자리_숫자면_유효하다() {
        assertThat(new ImportProperties(true, "/tmp", List.of(), 1000, "202606").hasValidSnapshot()).isTrue();
    }

    @Test
    void snapshot이_없거나_형식이_틀리면_무효다() {
        assertThat(new ImportProperties(true, "/tmp", List.of(), 1000, null).hasValidSnapshot()).isFalse();
        assertThat(new ImportProperties(true, "/tmp", List.of(), 1000, "2026Q2").hasValidSnapshot()).isFalse();
        assertThat(new ImportProperties(true, "/tmp", List.of(), 1000, "20260").hasValidSnapshot()).isFalse();
    }
}
