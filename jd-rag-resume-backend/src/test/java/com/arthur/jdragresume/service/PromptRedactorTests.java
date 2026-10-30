package com.arthur.jdragresume.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PromptRedactorTests {
    @ParameterizedTest
    @ValueSource(strings = {"13812345678", "+8613812345678", "8613812345678", "+86 13812345678",
            "86-138-1234-5678", "138 1234 5678", "138-1234-5678", "+86 138 1234 5678", "19987654321"})
    void mainlandPhonesAreRedacted(String phone) {
        assertEquals("电话：[手机号]，", PromptRedactor.redact("电话：" + phone + "，"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"arthur@example.com", "first.last+tag@example.co.uk", "user_name-01@sub.example.com",
            "git@github.com", "13812345678@example.com"})
    void dottedDomainEmailsIncludingBareGitAddressAreRedacted(String email) {
        assertEquals("邮箱：[邮箱]，", PromptRedactor.redact("邮箱：" + email + "，"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"JDK 17", "Spring Boot 3.5", "端口 8080", "QPS 12000", "20260921", "1.8.0_292",
            "192.168.1.10", "git@github.com:org/repo.git", "ssh://git@github.com:22/org/repo.git", "user@host",
            "@Transactional", "@RequestBody", "013812345678", "138123456789", "1238613812345678", "12812345678"})
    void technicalTextSshAndLongerNumbersRemainVerbatim(String text) {
        assertEquals(text, PromptRedactor.redact(text));
    }

    @Test
    void repeatedRedactionIsStableAndLeavesOtherIdentityDetailsAlone() {
        String text = "张三，北京大学，示例公司，13812345678 / a@example.com，[手机号] [邮箱]";
        String expected = "张三，北京大学，示例公司，[手机号] / [邮箱]，[手机号] [邮箱]";
        assertEquals(expected, PromptRedactor.redact(text));
        assertEquals(expected, PromptRedactor.redact(PromptRedactor.redact(text)));
        assertEquals("", PromptRedactor.redact(""));
        assertNull(PromptRedactor.redact((String) null));
    }
}
