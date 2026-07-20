package io.github.weidongxu.photo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adjustment values for developing a RAW into a JPEG, expressed in editor-neutral terms (white
 * balance, tint, exposure, contrast, saturation, highlight/shadow recovery, tone curve).
 *
 * <p>Every field is optional: an unset field leaves the developer's neutral default. {@link #neutral()}
 * sets nothing and yields the <em>baseline</em> develop (RAW → JPEG with no adjustment). The same
 * type expresses both the baseline (neutral) and the adjusted develop — they are the same operation
 * with different settings.</p>
 *
 * <p>Sign conventions (matching common photo editors): positive {@code highlights} recovers/darkens
 * blown highlights; positive {@code shadows} lifts shadows; {@code exposureEv} is in stops;
 * {@code tint} is a green–magenta multiplier where {@code 1.0} is neutral (&gt;1 greener). The tone
 * curve is a list of {@code {x, y}} control points in {@code [0, 1]}, ascending in {@code x}.</p>
 */
public final class DevelopSettings {

    private final Integer whiteBalanceTempK;
    private final Double tint;
    private final Double exposureEv;
    private final Integer contrast;
    private final Integer saturation;
    private final Integer highlights;
    private final Integer shadows;
    private final List<double[]> toneCurve;
    private final Integer maxLongEdgePx;

    private DevelopSettings(Builder builder) {
        this.whiteBalanceTempK = builder.whiteBalanceTempK;
        this.tint = builder.tint;
        this.exposureEv = builder.exposureEv;
        this.contrast = builder.contrast;
        this.saturation = builder.saturation;
        this.highlights = builder.highlights;
        this.shadows = builder.shadows;
        this.toneCurve = Collections.unmodifiableList(new ArrayList<>(builder.toneCurve));
        this.maxLongEdgePx = builder.maxLongEdgePx;
    }

    /** The baseline develop: no adjustment (all fields unset). */
    public static DevelopSettings neutral() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses the JSON the vision advice step returns into settings. Recognised keys (all optional):
     * {@code white_balance_temp_k}, {@code tint}, {@code exposure_ev}, {@code contrast},
     * {@code saturation}, {@code highlights}, {@code shadows}, and {@code tone_curve} (array of
     * {@code [x, y]} pairs). Unknown keys are ignored; missing keys stay neutral.
     */
    public static DevelopSettings fromJson(String json, ObjectMapper mapper) throws java.io.IOException {
        if (json == null || json.isBlank()) {
            return neutral();
        }
        return fromJsonNode(mapper.readTree(json));
    }

    /** {@link #fromJson(String, ObjectMapper)} for an already-parsed node. */
    public static DevelopSettings fromJsonNode(JsonNode node) {
        Builder b = builder();
        if (node == null || !node.isObject()) {
            return b.build();
        }
        if (node.hasNonNull("white_balance_temp_k")) {
            b.whiteBalanceTempK(node.get("white_balance_temp_k").asInt());
        }
        if (node.hasNonNull("tint")) {
            b.tint(node.get("tint").asDouble());
        }
        if (node.hasNonNull("exposure_ev")) {
            b.exposureEv(node.get("exposure_ev").asDouble());
        }
        if (node.hasNonNull("contrast")) {
            b.contrast(node.get("contrast").asInt());
        }
        if (node.hasNonNull("saturation")) {
            b.saturation(node.get("saturation").asInt());
        }
        if (node.hasNonNull("highlights")) {
            b.highlights(node.get("highlights").asInt());
        }
        if (node.hasNonNull("shadows")) {
            b.shadows(node.get("shadows").asInt());
        }
        JsonNode curve = node.get("tone_curve");
        if (curve != null && curve.isArray()) {
            for (JsonNode point : curve) {
                if (point.isArray() && point.size() >= 2) {
                    b.addToneCurvePoint(point.get(0).asDouble(), point.get(1).asDouble());
                }
            }
        }
        if (node.hasNonNull("max_long_edge_px")) {
            b.maxLongEdgePx(node.get("max_long_edge_px").asInt());
        }
        return b.build();
    }

    /** @return {@code true} when nothing is set — the baseline develop. */
    public boolean isNeutral() {
        return whiteBalanceTempK == null && tint == null && exposureEv == null && contrast == null
                && saturation == null && highlights == null && shadows == null && toneCurve.isEmpty()
                && maxLongEdgePx == null;
    }

    public Integer getWhiteBalanceTempK() {
        return whiteBalanceTempK;
    }

    public Double getTint() {
        return tint;
    }

    public Double getExposureEv() {
        return exposureEv;
    }

    public Integer getContrast() {
        return contrast;
    }

    public Integer getSaturation() {
        return saturation;
    }

    public Integer getHighlights() {
        return highlights;
    }

    public Integer getShadows() {
        return shadows;
    }

    /** Tone-curve control points as {@code {x, y}} pairs in {@code [0, 1]}; empty when unset. */
    public List<double[]> getToneCurve() {
        return toneCurve;
    }

    /**
     * The maximum output long-edge (larger of width/height) in pixels; the image is downscaled to
     * fit, preserving aspect ratio (never upscaled). {@code null} keeps full sensor resolution. A
     * value around 1024 is enough for the model's advice step and for a quick preview.
     */
    public Integer getMaxLongEdgePx() {
        return maxLongEdgePx;
    }

    public static final class Builder {
        private Integer whiteBalanceTempK;
        private Double tint;
        private Double exposureEv;
        private Integer contrast;
        private Integer saturation;
        private Integer highlights;
        private Integer shadows;
        private final List<double[]> toneCurve = new ArrayList<>();
        private Integer maxLongEdgePx;

        public Builder whiteBalanceTempK(Integer kelvin) {
            this.whiteBalanceTempK = kelvin;
            return this;
        }

        public Builder tint(Double greenMagenta) {
            this.tint = greenMagenta;
            return this;
        }

        public Builder exposureEv(Double stops) {
            this.exposureEv = stops;
            return this;
        }

        public Builder contrast(Integer contrast) {
            this.contrast = contrast;
            return this;
        }

        public Builder saturation(Integer saturation) {
            this.saturation = saturation;
            return this;
        }

        public Builder highlights(Integer highlights) {
            this.highlights = highlights;
            return this;
        }

        public Builder shadows(Integer shadows) {
            this.shadows = shadows;
            return this;
        }

        public Builder addToneCurvePoint(double x, double y) {
            this.toneCurve.add(new double[] {x, y});
            return this;
        }

        public Builder maxLongEdgePx(Integer maxLongEdgePx) {
            if (maxLongEdgePx != null && maxLongEdgePx < 1) {
                throw new IllegalArgumentException("maxLongEdgePx must be >= 1");
            }
            this.maxLongEdgePx = maxLongEdgePx;
            return this;
        }

        public DevelopSettings build() {
            return new DevelopSettings(this);
        }
    }
}
