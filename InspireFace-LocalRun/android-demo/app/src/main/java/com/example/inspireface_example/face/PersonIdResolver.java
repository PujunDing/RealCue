package com.example.inspireface_example.face;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps short-lived camera {@code track_id}s to persistent gallery {@code person_id}s.
 *
 * <p><b>Pure Java by design.</b> This class has no Android framework types and no
 * InspireFace native handles, so the entire ID-assignment policy can be exercised
 * exhaustively on a plain JVM (see {@code PersonIdResolverTest}). Production code
 * supplies a {@link Gallery} adapter that bridges to {@link FaceRepository} /
 * FeatureHub on the single CameraX analysis thread.
 *
 * <p>The policy has three confidence zones:
 * <ul>
 *   <li>{@code >= matchThreshold}: definite hit, bind the track to that person;</li>
 *   <li>{@code < newThreshold}: definite stranger, count consecutive misses and
 *       auto-enroll only after {@code missesToEnroll} of them;</li>
 *   <li>between the two: gray zone, keep sampling, do nothing.</li>
 * </ul>
 * A {@code person_id} already claimed by another track in the same frame is never
 * handed out twice (anti-cross-talk). Bindings expire after {@code trackTtlMs},
 * but the gallery record itself is never removed here: a person who fully leaves
 * the frame and comes back is recognised again from the persistent gallery, which
 * is exactly the "leave and return keeps P001" requirement.
 *
 * <p><b>Threshold caveat.</b> {@link #DEFAULT_MATCH_THRESHOLD} /
 * {@link #DEFAULT_NEW_THRESHOLD} are heuristic placeholders derived from the SDK's
 * recommended cosine threshold and the B0 calibration delta. They are NOT measured
 * values: B0 has not yet collected two real human subjects. Call
 * {@link #configureThresholds(float, float)} once real thresholds are available.
 *
 * @param <F> feature handle produced by the caller (e.g. {@code FaceFeature})
 * @param <C> per-frame context forwarded into enrollment (e.g. a crop source)
 */
public final class PersonIdResolver<F, C> {

    public enum Decision {
        /** Bound to an existing gallery identity (new hit or an already-bound track). */
        MATCHED,
        /** Gray zone, waiting for more evidence; no person id yet. */
        PENDING,
        /** A brand new identity was written into the gallery. */
        ENROLLED,
        /** No usable feature, or the candidate id is already taken this frame. */
        SKIPPED,
        /** Enrollment was attempted but the gallery rejected it; safe to retry. */
        ENROLL_FAILED
    }

    /** A gallery search hit. {@code personId < 0} means "no candidate". */
    public static final class Candidate {
        public final long personId;
        public final float confidence;

        public Candidate(long personId, float confidence) {
            this.personId = personId;
            this.confidence = confidence;
        }
    }

    /**
     * Bridge to the persistent gallery. Every call happens on the camera analysis
     * thread, so implementations may safely use thread-confined native handles.
     */
    public interface Gallery<F, C> {
        /** @return best hit, or {@code null} / negative id when the gallery has no match. */
        Candidate bestMatch(F feature);

        /** @return the newly created person id, or {@code -1} when persistence failed. */
        long enroll(F feature, C context);
    }

    public static final class Result {
        public final Decision decision;
        /** {@code -1} when no person id is assigned. */
        public final long personId;
        /** Best cosine similarity for this frame; {@link Float#NaN} when not searched. */
        public final float confidence;

        Result(Decision decision, long personId, float confidence) {
            this.decision = decision;
            this.personId = personId;
            this.confidence = confidence;
        }

        public boolean hasPersonId() {
            return personId >= 0L;
        }

        /** Display label used on the live overlay, e.g. {@code P001}. */
        public String label() {
            return hasPersonId() ? formatLabel(personId) : "…";
        }

        @Override
        public String toString() {
            return decision + "(person=" + personId + ", conf=" + confidence + ")";
        }
    }

    /** SDK recommended cosine threshold is ~0.48 for Pikachu; B0 new-delta is 0.12. */
    public static final float DEFAULT_MATCH_THRESHOLD = 0.48f;
    public static final float DEFAULT_NEW_THRESHOLD = 0.36f;
    public static final int DEFAULT_MISSES_TO_ENROLL = 3;
    public static final long DEFAULT_TRACK_TTL_MS = 2_000L;

    private static final Result SKIPPED = new Result(Decision.SKIPPED, -1L, Float.NaN);

    private final Gallery<F, C> gallery;
    private final Map<Integer, Binding> bindings = new HashMap<>();
    private final Map<Integer, Integer> consecutiveMisses = new HashMap<>();
    private final Set<Long> claimedThisFrame = new HashSet<>();

    private float matchThreshold = DEFAULT_MATCH_THRESHOLD;
    private float newThreshold = DEFAULT_NEW_THRESHOLD;
    private int missesToEnroll = DEFAULT_MISSES_TO_ENROLL;
    private long trackTtlMs = DEFAULT_TRACK_TTL_MS;
    private long enrolledCount;

    private static final class Binding {
        final long personId;
        long lastSeenMs;

        Binding(long personId, long lastSeenMs) {
            this.personId = personId;
            this.lastSeenMs = lastSeenMs;
        }
    }

    public PersonIdResolver(Gallery<F, C> gallery) {
        if (gallery == null) {
            throw new IllegalArgumentException("gallery must not be null");
        }
        this.gallery = gallery;
    }

    /**
     * Starts a new camera frame: releases per-frame id claims and drops stale track
     * bindings. The persistent gallery is untouched.
     */
    public void beginFrame(long nowMs) {
        claimedThisFrame.clear();
        Iterator<Map.Entry<Integer, Binding>> it = bindings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Binding> entry = it.next();
            if (elapsed(entry.getValue().lastSeenMs, nowMs) > trackTtlMs) {
                int trackId = entry.getKey();
                it.remove();
                consecutiveMisses.remove(trackId);
            }
        }
    }

    /**
     * Resolves one tracked face. Must be called after {@link #beginFrame(long)} and
     * only from the analysis thread.
     *
     * @param trackId short-lived SDK track id (stable only within one camera session)
     * @param feature freshly extracted feature, or {@code null} if extraction failed
     * @param context opaque data forwarded to {@link Gallery#enroll(Object, Object)}
     */
    public Result resolve(int trackId, F feature, C context, long nowMs) {
        if (feature == null) {
            return SKIPPED;
        }

        Binding bound = bindings.get(trackId);
        if (bound != null) {
            bound.lastSeenMs = nowMs;
            claimedThisFrame.add(bound.personId);
            return new Result(Decision.MATCHED, bound.personId, Float.NaN);
        }

        Candidate best = gallery.bestMatch(feature);
        long hitId = best == null ? -1L : best.personId;
        float confidence = best == null ? Float.NEGATIVE_INFINITY : best.confidence;

        if (hitId >= 0L && confidence >= matchThreshold) {
            if (claimedThisFrame.contains(hitId)) {
                // Another face in this same frame already owns that identity. Never
                // assign one person id to two boxes -> prevents cross-talk / 串号.
                return new Result(Decision.SKIPPED, -1L, confidence);
            }
            bind(trackId, hitId, nowMs);
            return new Result(Decision.MATCHED, hitId, confidence);
        }

        // NaN is neither >= matchThreshold nor < newThreshold, so it lands in the
        // gray zone and never triggers a false enrollment.
        if (confidence < newThreshold) {
            int misses = valueOrZero(consecutiveMisses.get(trackId)) + 1;
            if (misses >= missesToEnroll) {
                long newId = gallery.enroll(feature, context);
                if (newId < 0L) {
                    consecutiveMisses.put(trackId, 0);
                    return new Result(Decision.ENROLL_FAILED, -1L, confidence);
                }
                consecutiveMisses.remove(trackId);
                bind(trackId, newId, nowMs);
                enrolledCount++;
                return new Result(Decision.ENROLLED, newId, confidence);
            }
            consecutiveMisses.put(trackId, misses);
            return new Result(Decision.PENDING, -1L, confidence);
        }

        // Gray zone: reset the miss streak so a brief blur cannot accumulate into a
        // spurious new identity.
        consecutiveMisses.remove(trackId);
        return new Result(Decision.PENDING, -1L, confidence);
    }

    /**
     * Replaces the heuristic thresholds with measured ones from B0 calibration.
     * Requires {@code 0 <= newThreshold <= matchThreshold <= 1}.
     */
    public void configureThresholds(float matchThreshold, float newThreshold) {
        if (Float.isNaN(matchThreshold) || Float.isNaN(newThreshold)
                || newThreshold < 0f || matchThreshold > 1f
                || newThreshold > matchThreshold) {
            throw new IllegalArgumentException(
                    "require 0 <= newThreshold <= matchThreshold <= 1, got match="
                            + matchThreshold + ", new=" + newThreshold);
        }
        this.matchThreshold = matchThreshold;
        this.newThreshold = newThreshold;
    }

    public void setMissesToEnroll(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("missesToEnroll must be >= 1");
        }
        this.missesToEnroll = count;
    }

    public void setTrackTtlMs(long ttlMs) {
        if (ttlMs < 0L) {
            throw new IllegalArgumentException("trackTtlMs must be >= 0");
        }
        this.trackTtlMs = ttlMs;
    }

    /** Drops in-memory bindings only. Gallery identities survive; used by "reset session". */
    public void clear() {
        bindings.clear();
        consecutiveMisses.clear();
        claimedThisFrame.clear();
    }

    public void forgetTrack(int trackId) {
        bindings.remove(trackId);
        consecutiveMisses.remove(trackId);
    }

    /**
     * Refreshes an already-bound track without re-extracting features or searching
     * the gallery. Called once per camera frame so a continuously visible person's
     * binding never expires, and so the per-frame anti-cross-talk claim stays held.
     *
     * @return {@code true} when the track was bound
     */
    public boolean touch(int trackId, long nowMs) {
        Binding binding = bindings.get(trackId);
        if (binding == null) {
            return false;
        }
        binding.lastSeenMs = nowMs;
        claimedThisFrame.add(binding.personId);
        return true;
    }

    /** @return the bound person id, or {@code -1} when this track is unbound. */
    public long personFor(int trackId) {
        Binding binding = bindings.get(trackId);
        return binding == null ? -1L : binding.personId;
    }

    public int activeBindings() {
        return bindings.size();
    }

    /** Diagnostics only: number of identities this resolver has auto-enrolled. */
    public long enrolledCount() {
        return enrolledCount;
    }

    public float matchThreshold() {
        return matchThreshold;
    }

    public float newThreshold() {
        return newThreshold;
    }

    /** Zero-padded person label, e.g. 1 -> {@code P001}, 1001 -> {@code P1001}. */
    public static String formatLabel(long personId) {
        if (personId < 0L) {
            return "…";
        }
        return String.format(Locale.ROOT, "P%03d", personId);
    }

    private void bind(int trackId, long personId, long nowMs) {
        bindings.put(trackId, new Binding(personId, nowMs));
        claimedThisFrame.add(personId);
        consecutiveMisses.remove(trackId);
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long elapsed(long fromMs, long toMs) {
        return toMs >= fromMs ? toMs - fromMs : 0L;
    }
}
