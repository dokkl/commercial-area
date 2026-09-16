package com.example.commercialarea.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** MethodArgumentTypeMismatchException을 만들 때 필요한 실제 MethodParameter를 얻기 위한 더미. */
    @SuppressWarnings("unused")
    private void dummy(int zoom) {
    }

    /**
     * 컨트롤러 없이도 검증 가능하다: MethodArgumentTypeMismatchException은 Spring이 실제로 던지는
     * 타입이고, 핸들러는 그 값을 받아 매핑만 하므로 컨트롤러 디스패치 없이도 실제 동작을 검증할 수 있다.
     * (컨트롤러가 실제로 이 예외를 던지는지는 Task 8에서 엔드투엔드로 검증한다.)
     */
    @Test
    void 파라미터_타입이_안맞으면_400_INVALID_PARAMETER를_반환한다() throws NoSuchMethodException {
        Method dummyMethod = getClass().getDeclaredMethod("dummy", int.class);
        MethodParameter parameter = new MethodParameter(dummyMethod, 0);
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "abc", int.class, "zoom", parameter, new NumberFormatException("abc"));

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("INVALID_PARAMETER");
        assertThat(response.getBody().message()).contains("zoom");
    }
}
