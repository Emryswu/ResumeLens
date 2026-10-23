package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.user.JobSearchPreferencesInput;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobSearchPreferences;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.JobSearchPreferencesRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JobSearchPreferencesServiceTests {
    @Test
    void savesOnlyUnderAuthenticatedUserAndPreservesMissingFields() throws Exception {
        AppUser user = new AppUser();
        var id = AppUser.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(user, 42L);

        Map<Long, JobSearchPreferences> rows = new HashMap<>();
        JobSearchPreferencesRepository repository = (JobSearchPreferencesRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { JobSearchPreferencesRepository.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(rows.get(args[0]));
                    case "save" -> {
                        JobSearchPreferences value = (JobSearchPreferences) args[0];
                        rows.put(value.getUserId(), value);
                        yield value;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        CurrentUserService current = new CurrentUserService(null) {
            @Override public AppUser getCurrentUser() { return user; }
        };
        var service = new JobSearchPreferencesService(repository, current);

        assertFalse(service.mine().configured());
        var saved = service.save(new JobSearchPreferencesInput(6, 5,
                LocalDate.of(2026, 11, 1), " 龙岗区 ", false));
        assertEquals(42L, rows.keySet().iterator().next());
        assertEquals("龙岗区", saved.preferredDistrict());
        assertEquals(5, saved.daysPerWeek());
        assertTrue(saved.configured());
        assertFalse(service.forUser(17L).configured(), "another user's preferences are not returned");

        var blank = service.save(new JobSearchPreferencesInput(null, null, null, null, false));
        assertNull(blank.availableMonths());
        assertNull(blank.earliestStartDate());
        assertEquals(1, rows.size(), "save replaces current user's record, it does not append");
    }

    @Test
    void rejectsHardDistrictWithoutAValue() {
        var repository = (JobSearchPreferencesRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { JobSearchPreferencesRepository.class },
                (proxy, method, args) -> { throw new AssertionError("must reject before touching repository"); });
        AppUser user = new AppUser();
        CurrentUserService current = new CurrentUserService(null) {
            @Override public AppUser getCurrentUser() { return user; }
        };
        var service = new JobSearchPreferencesService(repository, current);
        var failure = assertThrows(BusinessException.class, () ->
                service.save(new JobSearchPreferencesInput(3, 4, null, "  ", true)));
        assertEquals("JOB_PREFERENCES_INVALID", failure.getCode());
    }
}
