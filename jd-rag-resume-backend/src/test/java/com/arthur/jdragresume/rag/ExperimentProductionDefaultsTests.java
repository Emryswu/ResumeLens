package com.arthur.jdragresume.rag;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures the two offline experiment runners cannot silently drift from production defaults. */
class ExperimentProductionDefaultsTests {
    @Test
    void sweepAndHoldoutMatchEveryProductionRagDefault() throws Exception {
        assertRunnerDefaults(productionProperties());
    }

    @Test
    void changingProductionTopKWouldFailTheGuard() throws Exception {
        Properties modified = productionProperties();
        modified.setProperty("app.rag.top-k", "${RAG_TOP_K:" + (new RagProperties().getTopK() + 1) + "}");
        AssertionError failure = assertThrows(AssertionError.class, () -> assertRunnerDefaults(modified));
        assertTrue(failure.getMessage().contains("app.rag.top-k"));
    }

    private static Properties productionProperties() throws Exception {
        Properties source = new Properties();
        try (InputStream stream = ExperimentProductionDefaultsTests.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertNotNull(stream, "application.properties must be on the test classpath");
            source.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return source;
    }

    private static void assertRunnerDefaults(Properties source) throws Exception {
        Method sweepFactory = ThresholdSweepExperimentTests.class.getDeclaredMethod("productionProperties");
        sweepFactory.setAccessible(true);
        RagProperties sweep = (RagProperties) sweepFactory.invoke(null);

        Method holdoutFactory = HoldoutExperimentTests.class.getDeclaredMethod(
                "productionProperties", HoldoutRunnerSupport.ProductionConfig.class);
        holdoutFactory.setAccessible(true);
        RagProperties holdout = (RagProperties) holdoutFactory.invoke(
                null, HoldoutRunnerSupport.loadProductionConfig());

        RagProperties defaults = new RagProperties();
        for (Field field : RagProperties.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            String key = "app.rag." + field.getName()
                    .replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
            String configured = source.getProperty(key);
            assertNotNull(configured, "production property missing: " + key);
            String raw = configured.trim();
            if (raw.startsWith("${")) {
                int separator = raw.indexOf(':');
                if (separator < 0 || !raw.endsWith("}")) {
                    throw new IllegalStateException("missing production fallback: " + key);
                }
                raw = raw.substring(separator + 1, raw.length() - 1);
            }
            Object expected = switch (field.getType().getName()) {
                case "int" -> Integer.parseInt(raw);
                case "double" -> Double.parseDouble(raw);
                case "boolean" -> Boolean.parseBoolean(raw);
                case "java.lang.String" -> raw;
                default -> throw new IllegalStateException("unhandled RagProperties field: " + field.getName());
            };
            assertEquals(expected, field.get(defaults), "RagProperties default drift: " + key);
            assertEquals(expected, field.get(sweep), "threshold sweep drift: " + key);
            assertEquals(expected, field.get(holdout), "holdout drift: " + key);
        }
    }
}
