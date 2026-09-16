package com.example.commercialarea.importer;

import com.example.commercialarea.store.Store;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

public class StoreCsvParser {

    /**
     * 이 CSV는 문자열 필드만 따옴표로 감싸고 숫자 필드는 감싸지 않는 혼합 형식이다.
     * 따라서 문자열 분리가 아니라 RFC4180 파서를 써야 한다.
     * 헤더 이름으로 매핑하므로 시도별 파일 간 컬럼 순서가 달라도 안전하다.
     */
    public static final CSVFormat FORMAT = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .build();

    public Store parse(CSVRecord r) {
        return new Store(
                required(r, "상가업소번호"),
                required(r, "상호명"),
                nullable(r, "지점명"),
                required(r, "상권업종대분류코드"),
                required(r, "상권업종대분류명"),
                required(r, "상권업종중분류코드"),
                required(r, "상권업종중분류명"),
                required(r, "상권업종소분류코드"),
                required(r, "상권업종소분류명"),
                required(r, "시도코드"),
                required(r, "시도명"),
                required(r, "시군구코드"),
                required(r, "시군구명"),
                nullable(r, "행정동코드"),
                nullable(r, "행정동명"),
                nullable(r, "지번주소"),
                nullable(r, "건물명"),
                nullable(r, "도로명주소"),
                nullable(r, "층정보"),
                coordinate(r, "경도"),
                coordinate(r, "위도")
        );
    }

    private static String required(CSVRecord r, String column) {
        String v = value(r, column);
        if (v == null) {
            throw new MalformedRowException("필수 항목 누락: " + column);
        }
        return v;
    }

    private static String nullable(CSVRecord r, String column) {
        return value(r, column);
    }

    private static double coordinate(CSVRecord r, String column) {
        String v = value(r, column);
        if (v == null) {
            throw new MalformedRowException("좌표 누락: " + column);
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new MalformedRowException("좌표 형식 오류: " + column + "=" + v);
        }
    }

    private static String value(CSVRecord r, String column) {
        if (!r.isMapped(column)) {
            return null;
        }
        String v = r.get(column);
        return (v == null || v.isBlank()) ? null : v;
    }
}
