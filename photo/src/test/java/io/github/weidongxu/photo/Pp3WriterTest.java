package io.github.weidongxu.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pp3WriterTest {

    @Test
    void neutralSettingsProduceNoProfile() {
        assertNull(Pp3Writer.toPp3(DevelopSettings.neutral()));
        assertTrue(DevelopSettings.neutral().isNeutral());
    }

    @Test
    void writesEverySectionForFullSettings() {
        DevelopSettings settings = DevelopSettings.builder()
                .whiteBalanceTempK(4800)
                .tint(1.15)
                .exposureEv(0.8)
                .contrast(15)
                .saturation(8)
                .highlights(45)
                .shadows(50)
                .addToneCurvePoint(0, 0)
                .addToneCurvePoint(0.25, 0.18)
                .addToneCurvePoint(0.75, 0.86)
                .addToneCurvePoint(1, 1)
                .build();

        String pp3 = Pp3Writer.toPp3(settings);

        assertTrue(pp3.contains("[White Balance]"));
        assertTrue(pp3.contains("Setting=Custom"));
        assertTrue(pp3.contains("Temperature=4800"));
        assertTrue(pp3.contains("Green=1.150000"));
        assertTrue(pp3.contains("[Exposure]"));
        assertTrue(pp3.contains("Compensation=0.800000"));
        assertTrue(pp3.contains("Contrast=15"));
        assertTrue(pp3.contains("Saturation=8"));
        assertTrue(pp3.contains("CurveMode=Standard"));
        assertTrue(pp3.contains("Curve=1;0.000000;0.000000;"));
        assertTrue(pp3.contains("[Shadows & Highlights]"));
        assertTrue(pp3.contains("Highlights=45"));
        assertTrue(pp3.contains("Shadows=50"));
    }

    @Test
    void omitsUnsetSections() {
        String pp3 = Pp3Writer.toPp3(DevelopSettings.builder().exposureEv(1.0).build());

        assertTrue(pp3.contains("[Exposure]"));
        assertTrue(pp3.contains("Compensation=1.000000"));
        assertFalse(pp3.contains("[White Balance]"));
        assertFalse(pp3.contains("[Shadows & Highlights]"));
        assertFalse(pp3.contains("Curve="));
    }

    @Test
    void parsesVisionAdviceJson() throws Exception {
        String json = "{\"white_balance_temp_k\":5200,\"tint\":0.95,\"exposure_ev\":-0.5,"
                + "\"contrast\":10,\"saturation\":-4,\"highlights\":60,\"shadows\":35,"
                + "\"tone_curve\":[[0,0],[0.5,0.55],[1,1]]}";

        DevelopSettings settings = DevelopSettings.fromJson(json, new ObjectMapper());

        assertFalse(settings.isNeutral());
        String pp3 = Pp3Writer.toPp3(settings);
        assertTrue(pp3.contains("Temperature=5200"));
        assertTrue(pp3.contains("Green=0.950000"));
        assertTrue(pp3.contains("Compensation=-0.500000"));
        assertTrue(pp3.contains("Highlights=60"));
        assertTrue(pp3.contains("Shadows=35"));
        assertTrue(pp3.contains("Curve=1;0.000000;0.000000;0.500000;0.550000;1.000000;1.000000;"));
    }

    @Test
    void emptyJsonIsNeutral() throws Exception {
        assertTrue(DevelopSettings.fromJson("", new ObjectMapper()).isNeutral());
        assertTrue(DevelopSettings.fromJson("{}", new ObjectMapper()).isNeutral());
    }
}
