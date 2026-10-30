package com.arthur.jdragresume.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The turn budgets are declared twice; the deployed default and the class default must not drift apart. */
class AgentPropertiesDefaultsTests {
    @Test
    void turnBudgetDefaultsMatchApplicationProperties() throws Exception {
        String source = Files.readString(Path.of("src/main/resources/application.properties"));
        AgentProperties defaults = new AgentProperties();
        assertEquals(defaults.getMaxSteps(), deployed(source, "app.agent.max-steps"));
        assertEquals(defaults.getMaxPlanningSteps(), deployed(source, "app.agent.max-planning-steps"));
        assertEquals(defaults.getMaxReplans(), deployed(source, "app.agent.max-replans"));
        assertEquals(defaults.getVerifiedJdMaxAgeDays(), deployed(source, "app.agent.verified-jd-max-age-days"));
    }

    private static int deployed(String source, String key) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(key) + "=\\$\\{\\w+:(\\d+)}$").matcher(source);
        assertTrue(matcher.find(), key + " must have an environment override with a numeric default");
        return Integer.parseInt(matcher.group(1));
    }
}
