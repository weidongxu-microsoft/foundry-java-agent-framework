package io.github.weidongxu.photo;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A crop rectangle expressed as normalized edges in {@code [0, 1]}, where {@code (0, 0)} is the
 * top-left corner and {@code (1, 1)} the bottom-right. Being resolution-independent, the same rect
 * applies to any develop of the image (preview or full size).
 *
 * <p>A rect is <em>meaningful</em> only when it actually trims the frame while keeping a sensible
 * area (see {@link #isMeaningful()}); a full-frame or degenerate rect is treated as "no crop".</p>
 */
public final class CropRect {

    private final double left;
    private final double top;
    private final double right;
    private final double bottom;

    public CropRect(double left, double top, double right, double bottom) {
        this.left = clamp01(left);
        this.top = clamp01(top);
        this.right = clamp01(right);
        this.bottom = clamp01(bottom);
    }

    /**
     * Parses a {@code {"left","top","right","bottom"}} object of normalized edges. Returns
     * {@code null} when the node is absent/not an object or the resulting rect is not
     * {@link #isMeaningful() meaningful}.
     */
    public static CropRect fromJsonNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        CropRect rect = new CropRect(
                edge(node, "left", 0.0),
                edge(node, "top", 0.0),
                edge(node, "right", 1.0),
                edge(node, "bottom", 1.0));
        return rect.isMeaningful() ? rect : null;
    }

    private static double edge(JsonNode node, String key, double fallback) {
        return node.hasNonNull(key) ? node.get(key).asDouble(fallback) : fallback;
    }

    public double getLeft() {
        return left;
    }

    public double getTop() {
        return top;
    }

    public double getRight() {
        return right;
    }

    public double getBottom() {
        return bottom;
    }

    public double getWidth() {
        return right - left;
    }

    public double getHeight() {
        return bottom - top;
    }

    /**
     * @return {@code true} when the rect actually trims the frame (an edge is inset by more than
     *     ~0.5%) yet keeps at least 40% of each dimension — i.e. a real, non-degenerate composition
     *     crop worth applying.
     */
    public boolean isMeaningful() {
        boolean keepsArea = getWidth() >= 0.4 && getHeight() >= 0.4;
        boolean trims = left > 0.005 || top > 0.005 || right < 0.995 || bottom < 0.995;
        return keepsArea && trims;
    }

    @Override
    public String toString() {
        return String.format(
                java.util.Locale.ROOT,
                "{left=%.3f, top=%.3f, right=%.3f, bottom=%.3f}",
                left, top, right, bottom);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
