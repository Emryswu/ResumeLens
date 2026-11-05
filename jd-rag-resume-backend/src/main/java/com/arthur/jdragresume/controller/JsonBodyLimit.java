package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.exception.BusinessException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

@ControllerAdvice
public class JsonBodyLimit extends RequestBodyAdviceAdapter {
    private static final int MAX_BYTES = 1024 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converter);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
                                           Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        if (input.getHeaders().getContentLength() > MAX_BYTES) throw tooLarge();
        byte[] bytes = input.getBody().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw tooLarge();
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    private static BusinessException tooLarge() {
        return new BusinessException("PAYLOAD_TOO_LARGE", "JSON request body is too large");
    }
}
