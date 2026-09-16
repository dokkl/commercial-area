package com.example.commercialarea.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.text.Normalizer;
import java.util.List;

@ConfigurationProperties(prefix = "app.import")
public record ImportProperties(boolean enabled, String dir, List<String> include, int batchSize) {
    public ImportProperties {
        if (include == null) include = List.of();
        if (batchSize <= 0) batchSize = 1000;
    }

    /**
     * include가 비어 있으면 모든 CSV를 적재한다.
     *
     * macOS에서 압축 해제된 CSV 파일명은 한글이 NFD(자모 분해) 형태로 저장되는 경우가 있는 반면,
     * application.yml/.env의 include 값은 NFC(완성형)다. String.contains는 정규화를 하지 않으므로
     * 두 값을 모두 NFC로 정규화한 뒤 비교해야 실제 파일명과 올바르게 매칭된다.
     */
    public boolean matches(String fileName) {
        if (include.isEmpty()) return true;
        String normalizedFileName = Normalizer.normalize(fileName, Normalizer.Form.NFC);
        return include.stream()
                .map(pattern -> Normalizer.normalize(pattern, Normalizer.Form.NFC))
                .anyMatch(normalizedFileName::contains);
    }
}
