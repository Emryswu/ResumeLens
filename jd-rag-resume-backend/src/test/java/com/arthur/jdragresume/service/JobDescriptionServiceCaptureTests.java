package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDescriptionServiceCaptureTests {
    private final AtomicReference<JobDescription> stored = new AtomicReference<>();
    private final AtomicLong ids = new AtomicLong();
    private JobDescriptionService service;

    @BeforeEach
    void setUp() {
        stored.set(null);
        ids.set(0L);
        AppUser user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);

        JobDescriptionRepository jobRepository = proxy(
                JobDescriptionRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "findByUserIdAndSourcePlatformAndSourceJobId" -> Optional.ofNullable(stored.get());
                    case "countByUserId" -> 0L;
                    case "save" -> {
                        JobDescription job = (JobDescription) args[0];
                        if (job.getId() == null) {
                            ReflectionTestUtils.setField(job, "id", ids.incrementAndGet());
                        }
                        stored.set(job);
                        yield job;
                    }
                    case "toString" -> "JobDescriptionRepositoryCaptureDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        AppUserRepository userRepository = proxy(
                AppUserRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "findByIdForUpdate" -> Optional.of(user);
                    case "toString" -> "AppUserRepositoryCaptureDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );

        service = new JobDescriptionService(
                jobRepository,
                fixedCurrentUser(user),
                userRepository,
                SemanticEmbeddingTestSupport.service(),
                null,
                200,
                java.time.Clock.systemDefaultZone()
        );
    }

    @Test
    void createsOneSourcedJobThenRecognizesTheSameCapture() {
        JobCaptureResponse created = service.capture(request("负责 Spring Boot 服务开发"));
        JobCaptureResponse duplicate = service.capture(request("负责  Spring Boot\n服务开发"));

        assertFalse(created.existingJob());
        assertFalse(created.contentChanged());
        assertTrue(duplicate.existingJob());
        assertFalse(duplicate.contentChanged());
        assertEquals(created.job().id(), duplicate.job().id());
        assertEquals("BOSS", duplicate.job().sourcePlatform());
        assertEquals("boss-key-1", duplicate.job().sourceJobId());
        assertNotNull(duplicate.job().contentFingerprint());
        assertNotNull(duplicate.job().lastSeenAt());
    }

    @Test
    void updatesTheExistingLibraryEntryWhenCapturedContentChanges() {
        JobCaptureResponse created = service.capture(request("旧 JD"));
        JobCaptureResponse changed = service.capture(request("新 JD：需要 Java 21"));

        assertTrue(changed.existingJob());
        assertTrue(changed.contentChanged());
        assertEquals(created.job().id(), changed.job().id());
        assertEquals("新 JD：需要 Java 21", changed.job().description());
    }

    private JobCaptureRequest request(String description) {
        return new JobCaptureRequest(
                "Java 后端工程师",
                "示例科技",
                "杭州",
                "全职",
                description,
                "Java、Spring Boot",
                "boss",
                "https://www.zhipin.com/job_detail/boss-key-1.html",
                "boss-key-1"
        );
    }

    private static CurrentUserService fixedCurrentUser(AppUser user) {
        return new CurrentUserService(proxy(
                AppUserRepository.class,
                (ignored, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                }
        )) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
