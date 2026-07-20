package io.github.weidongxu.photo;

import java.util.List;
import java.util.Locale;

/**
 * Renders {@link DevelopSettings} into a RawTherapee {@code .pp3} processing profile. RawTherapee
 * builds the final values on top of neutral defaults, so a partial pp3 that names only the changed
 * sections is sufficient — unset settings are simply omitted.
 *
 * <p>Section/key names follow RawTherapee 5.12 (verified against a dumped default profile). The
 * mapping is: white balance/tint → {@code [White Balance]}; exposure/contrast/saturation and the
 * tone curve → {@code [Exposure]}; highlight/shadow recovery → {@code [Shadows & Highlights]}.</p>
 */
public final class Pp3Writer {

    private Pp3Writer() {
    }

    /** @return the pp3 text for {@code settings}, or {@code null} when neutral (no profile needed). */
    public static String toPp3(DevelopSettings settings) {
        if (settings == null || settings.isNeutral()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Version]\n");
        sb.append("AppVersion=5.12\n");
        sb.append("Version=351\n");

        if (settings.getWhiteBalanceTempK() != null || settings.getTint() != null) {
            sb.append("\n[White Balance]\n");
            sb.append("Enabled=true\n");
            sb.append("Setting=Custom\n");
            if (settings.getWhiteBalanceTempK() != null) {
                sb.append("Temperature=").append(settings.getWhiteBalanceTempK()).append('\n');
            }
            if (settings.getTint() != null) {
                sb.append("Green=").append(num(settings.getTint())).append('\n');
            }
        }

        boolean exposure = settings.getExposureEv() != null || settings.getContrast() != null
                || settings.getSaturation() != null || !settings.getToneCurve().isEmpty();
        if (exposure) {
            sb.append("\n[Exposure]\n");
            sb.append("Auto=false\n");
            if (settings.getExposureEv() != null) {
                sb.append("Compensation=").append(num(settings.getExposureEv())).append('\n');
            }
            if (settings.getContrast() != null) {
                sb.append("Contrast=").append(settings.getContrast()).append('\n');
            }
            if (settings.getSaturation() != null) {
                sb.append("Saturation=").append(settings.getSaturation()).append('\n');
            }
            if (!settings.getToneCurve().isEmpty()) {
                sb.append("CurveMode=Standard\n");
                sb.append("Curve=").append(curve(settings.getToneCurve())).append('\n');
            }
        }

        if (settings.getHighlights() != null || settings.getShadows() != null) {
            sb.append("\n[Shadows & Highlights]\n");
            sb.append("Enabled=true\n");
            if (settings.getHighlights() != null) {
                sb.append("Highlights=").append(settings.getHighlights()).append('\n');
            }
            if (settings.getShadows() != null) {
                sb.append("Shadows=").append(settings.getShadows()).append('\n');
            }
        }

        if (settings.getMaxLongEdgePx() != null) {
            int edge = settings.getMaxLongEdgePx();
            sb.append("\n[Resize]\n");
            sb.append("Enabled=true\n");
            sb.append("AppliesTo=Cropped area\n");
            sb.append("Method=Lanczos\n");
            sb.append("DataSpecified=3\n");
            sb.append("Width=").append(edge).append('\n');
            sb.append("Height=").append(edge).append('\n');
            sb.append("LongEdge=").append(edge).append('\n');
            sb.append("AllowUpscaling=false\n");
        }
        return sb.toString();
    }

    /**
     * Encodes control points as a RawTherapee diagonal spline curve: {@code 1;x1;y1;x2;y2;...;}
     * where the leading {@code 1} selects the spline curve type.
     */
    private static String curve(List<double[]> points) {
        StringBuilder sb = new StringBuilder("1;");
        for (double[] point : points) {
            sb.append(num(clamp01(point[0]))).append(';').append(num(clamp01(point[1]))).append(';');
        }
        return sb.toString();
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
