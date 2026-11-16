package com.example.inspireface_example.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Project-added, dependency-free score statistics for B0 threshold calibration.
 *
 * Kept deliberately free of Android and InspireFace types so the arithmetic can
 * be asserted exhaustively by instrumentation tests without loading any native
 * model. The device-side test only has to produce two lists of cosine scores:
 * same-person pairs and different-person pairs.
 */
final class CalibrationMath {

    /** Gap between the accept threshold and the "definitely new person" threshold. */
    static final float DEFAULT_NEW_DELTA = 0.12f;

    private CalibrationMath() {}

    static final class Result {
        final int sameCount;
        final int diffCount;
        final float sameMin;
        final float sameMax;
        final float sameMean;
        final float sameP05;
        final float diffMin;
        final float diffMax;
        final float diffMean;
        final float diffP95;
        final boolean hasDiffData;
        /** True only when every same-person pair scores above every different-person pair. */
        final boolean separable;
        /** sameMin - diffMax; negative means the two distributions overlap. */
        final float margin;
        final float matchThreshold;
        final float newThreshold;
        /** Fraction of same-person pairs accepted at matchThreshold. */
        final float recallAtMatch;
        /** Fraction of different-person pairs wrongly accepted at matchThreshold. */
        final float falseAcceptAtMatch;

        Result(int sameCount, int diffCount, float sameMin, float sameMax,
               float sameMean, float sameP05, float diffMin, float diffMax,
               float diffMean, float diffP95, boolean separable, float margin,
               float matchThreshold, float newThreshold,
               float recallAtMatch, float falseAcceptAtMatch) {
            this.sameCount = sameCount;
            this.diffCount = diffCount;
            this.sameMin = sameMin;
            this.sameMax = sameMax;
            this.sameMean = sameMean;
            this.sameP05 = sameP05;
            this.diffMin = diffMin;
            this.diffMax = diffMax;
            this.diffMean = diffMean;
            this.diffP95 = diffP95;
            this.hasDiffData = diffCount > 0;
            this.separable = separable;
            this.margin = margin;
            this.matchThreshold = matchThreshold;
            this.newThreshold = newThreshold;
            this.recallAtMatch = recallAtMatch;
            this.falseAcceptAtMatch = falseAcceptAtMatch;
        }

        String asJson() {
            return String.format(Locale.ROOT,
                    "{\"sameCount\":%d,\"diffCount\":%d,"
                            + "\"sameMin\":%.4f,\"sameMax\":%.4f,\"sameMean\":%.4f,\"sameP05\":%.4f,"
                            + "\"diffMin\":%.4f,\"diffMax\":%.4f,\"diffMean\":%.4f,\"diffP95\":%.4f,"
                            + "\"hasDiffData\":%b,\"separable\":%b,\"margin\":%.4f,"
                            + "\"matchThreshold\":%.4f,\"newThreshold\":%.4f,"
                            + "\"recallAtMatch\":%.4f,\"falseAcceptAtMatch\":%.4f}",
                    sameCount, diffCount, sameMin, sameMax, sameMean, sameP05,
                    diffMin, diffMax, diffMean, diffP95, hasDiffData, separable,
                    margin, matchThreshold, newThreshold,
                    recallAtMatch, falseAcceptAtMatch);
        }
    }

    static Result analyze(List<Float> sameScores, List<Float> diffScores) {
        return analyze(sameScores, diffScores, DEFAULT_NEW_DELTA);
    }

    /**
     * Derives T_match / T_new from two score distributions.
     *
     * With separable data the midpoint of the gap is used. When the
     * distributions overlap there is no threshold that is both fully sensitive
     * and fully specific, so we prioritise zero false accepts (pick just above
     * diffP95) and expose the resulting recall loss in the result. With no
     * different-person data at all we can only guarantee same-person recall,
     * and the returned matchThreshold is deliberately flagged by hasDiffData.
     */
    static Result analyze(List<Float> sameScores, List<Float> diffScores, float newDelta) {
        if (sameScores == null || sameScores.isEmpty()) {
            throw new IllegalArgumentException("sameScores must not be empty");
        }
        if (diffScores == null) {
            diffScores = Collections.emptyList();
        }
        List<Float> same = sortedCopy(sameScores);
        List<Float> diff = sortedCopy(diffScores);

        float sameMin = same.get(0);
        float sameMax = same.get(same.size() - 1);
        float sameMean = mean(same);
        float sameP05 = percentile(same, 0.05f);

        boolean hasDiff = !diff.isEmpty();
        float diffMin = hasDiff ? diff.get(0) : Float.NaN;
        float diffMax = hasDiff ? diff.get(diff.size() - 1) : Float.NaN;
        float diffMean = hasDiff ? mean(diff) : Float.NaN;
        float diffP95 = hasDiff ? percentile(diff, 0.95f) : Float.NaN;

        boolean separable = hasDiff && sameMin > diffMax;
        float margin = hasDiff ? sameMin - diffMax : Float.NaN;

        float matchThreshold;
        if (!hasDiff) {
            matchThreshold = clamp01(sameMin - 0.02f);
        } else if (separable) {
            matchThreshold = clamp01((sameMin + diffMax) / 2f);
        } else {
            matchThreshold = clamp01(Math.min(sameMax, diffP95 + 0.02f));
        }
        float newThreshold = clamp01(matchThreshold - Math.max(0f, newDelta));

        return new Result(
                same.size(), diff.size(),
                sameMin, sameMax, sameMean, sameP05,
                diffMin, diffMax, diffMean, diffP95,
                separable, margin, matchThreshold, newThreshold,
                fractionAtOrAbove(sameScores, matchThreshold),
                hasDiff ? fractionAtOrAbove(diffScores, matchThreshold) : Float.NaN);
    }

    /** Linear-interpolated percentile; p is clamped into [0,1]. */
    static float percentile(List<Float> sortedAscending, float p) {
        if (sortedAscending.isEmpty()) {
            return Float.NaN;
        }
        if (sortedAscending.size() == 1) {
            return sortedAscending.get(0);
        }
        float pos = (sortedAscending.size() - 1)
                * Math.max(0f, Math.min(1f, p));
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sortedAscending.size() - 1, lo + 1);
        float frac = pos - lo;
        return sortedAscending.get(lo)
                + (sortedAscending.get(hi) - sortedAscending.get(lo)) * frac;
    }

    private static List<Float> sortedCopy(List<Float> source) {
        List<Float> copy = new ArrayList<>(source);
        for (Float value : copy) {
            if (value == null || Float.isNaN(value)) {
                throw new IllegalArgumentException("scores must be finite numbers");
            }
        }
        Collections.sort(copy);
        return copy;
    }

    private static float mean(List<Float> sorted) {
        float sum = 0f;
        for (Float value : sorted) {
            sum += value;
        }
        return sum / sorted.size();
    }

    private static float fractionAtOrAbove(List<Float> values, float threshold) {
        if (values.isEmpty()) {
            return Float.NaN;
        }
        int hits = 0;
        for (Float value : values) {
            if (value >= threshold) {
                hits++;
            }
        }
        return (float) hits / values.size();
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
