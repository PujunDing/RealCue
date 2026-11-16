package com.example.inspireface_example.view;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import com.example.inspireface_example.face.FaceRepository;
import com.example.inspireface_example.face.PersonIdResolver;
import com.insightface.sdk.inspireface.InspireFace;
import com.insightface.sdk.inspireface.base.FaceFeature;
import com.insightface.sdk.inspireface.base.FaceRect;
import com.insightface.sdk.inspireface.base.ImageStream;
import com.insightface.sdk.inspireface.base.MultipleFaceData;
import com.insightface.sdk.inspireface.base.Point2f;
import com.insightface.sdk.inspireface.base.Session;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Runs configurable multi-face tracking and submits one batched GL frame per camera frame.
 *
 * <p>Two label modes, selected by constructor:
 * <ul>
 *   <li><b>Legacy</b> (no {@link FaceRepository}): badges show the SDK's short-lived
 *       {@code trackId} as {@code "ID n"}. Kept for callers that need raw tracker ids.</li>
 *   <li><b>Stable</b> (repository supplied): badges show the persistent gallery id as
 *       {@code P001}. Every stable face is resolved through {@link PersonIdResolver}
 *       against FeatureHub, so a person who fully leaves and returns keeps the same id.
 *       Dense landmarks, box smoothing, per-track colors, FPS reporting and all
 *       user-tunable Session parameters stay exactly as before.</li>
 * </ul>
 *
 * <p>FeatureHub is a process-wide singleton guarded by {@code FaceRepository.HUB_LOCK};
 * all native work stays on the single CameraX analysis executor (no thread hops).
 */
public final class FaceTrackingAnalyzer extends UprightFaceCameraAnalyzer {

    private static final String TAG = "RealCueTracking";

    public interface Listener {
        void onSessionReady();

        /** Throttled callback on the camera analysis executor. */
        void onStats(double fps, long latencyMs);

        void onSessionError();
    }

    private static final long REPORT_INTERVAL_MS = 300L;
    private static final long FEATURE_INTERVAL_MS = 320L;
    private static final int MIN_TRACK_COUNT = 3;
    private static final float MIN_FACE_WIDTH_RATIO = 0.12f;
    private static final String UNKNOWN_LABEL = "…";
    private static final int[] TRACK_COLORS = {
            0xFF00E5A0, 0xFFFFC400, 0xFF40C4FF, 0xFFFF6E9C, 0xFFC6A5FF,
            0xFFFF8A65, 0xFF76FF03, 0xFF18FFFF, 0xFFFFD180, 0xFFEA80FC
    };

    private final FaceTrackingGlView overlay;
    private final TrackIdOverlayView trackIdOverlay;
    private final int detectMode;
    private final int detectPixelLevel;
    private final int maxFaces;
    private final int minimumFacePixelSize;
    private final Listener listener;
    private final float[] pointVertices = new float[
            FaceTrackingGlView.MAX_FACES * FaceTrackingGlView.LANDMARKS_PER_FACE
                    * FaceTrackingGlView.FLOATS_PER_VERTEX];
    private final float[] boxVertices = new float[
            FaceTrackingGlView.MAX_FACES * FaceTrackingGlView.BOX_VERTICES_PER_FACE
                    * FaceTrackingGlView.FLOATS_PER_VERTEX];
    private final int[] labelTrackIds = new int[FaceTrackingGlView.MAX_FACES];
    private final String[] labelTexts = new String[FaceTrackingGlView.MAX_FACES];
    private final int[] labelColors = new int[FaceTrackingGlView.MAX_FACES];
    private final float[] labelRects = new float[FaceTrackingGlView.MAX_FACES * 4];
    private final SparseIntArray colorByTrackId = new SparseIntArray();
    private final TrackBoxSmoother boxSmoother = new TrackBoxSmoother();
    private final float[] smoothedRect = new float[4];
    private int nextColorOrdinal;

    // Stable-label state. Null/empty when running in legacy track-id mode.
    @Nullable
    private final FaceRepository repository;
    @Nullable
    private final PersonIdResolver<FaceFeature, EnrollContext> resolver;
    private final Map<Integer, String> labelByTrack = new HashMap<>();
    private final Set<Integer> seenTracks = new HashSet<>();
    private long lastFeatureMs;

    private volatile boolean mirrored = true;
    private long fpsWindowStart;
    private int fpsWindowFrames;
    private double fps;
    private double latencyEmaMs;
    private long lastReport;

    /** Legacy mode: badges show short-lived SDK track ids ("ID n"). */
    public FaceTrackingAnalyzer(FaceTrackingGlView overlay,
                                TrackIdOverlayView trackIdOverlay,
                                int detectMode, int detectPixelLevel,
                                int maxFaces, int minimumFacePixelSize,
                                Listener listener) {
        this(overlay, trackIdOverlay, detectMode, detectPixelLevel,
                maxFaces, minimumFacePixelSize, listener, null);
    }

    /**
     * @param repository when non-null, enables persistent gallery labels (P001).
     *                   Must already be {@link FaceRepository#open()}-ed by the caller.
     */
    public FaceTrackingAnalyzer(FaceTrackingGlView overlay,
                                TrackIdOverlayView trackIdOverlay,
                                int detectMode, int detectPixelLevel,
                                int maxFaces, int minimumFacePixelSize,
                                Listener listener,
                                @Nullable FaceRepository repository) {
        this.overlay = overlay;
        this.trackIdOverlay = trackIdOverlay;
        this.detectMode = detectMode;
        this.detectPixelLevel = detectPixelLevel;
        this.maxFaces = maxFaces;
        this.minimumFacePixelSize = minimumFacePixelSize;
        this.listener = listener;
        this.repository = repository;
        this.resolver = repository == null ? null
                : new PersonIdResolver<>(new GalleryAdapter());
    }

    /** True when this analyzer renders persistent gallery ids instead of track ids. */
    public boolean usesStablePersonIds() {
        return repository != null;
    }

    @Override
    protected Session createSession() {
        // Recognition is required to extract a feature per face token; without it
        // FeatureHub search / auto-enrollment is impossible.
        return FaceEngine.createFaceTrackingSession(
                detectMode, detectPixelLevel, maxFaces, minimumFacePixelSize,
                usesStablePersonIds());
    }

    @Override
    protected void onSessionReady() {
        if (usesStablePersonIds()) {
            Log.i(TAG, "SESSION_READY stableIds=true match="
                    + resolver.matchThreshold() + " new=" + resolver.newThreshold()
                    + " maxFaces=" + maxFaces + " mode=" + detectMode);
        }
        listener.onSessionReady();
    }

    @Override
    protected void onFaces(Session session, ImageStream stream,
                           @Nullable MultipleFaceData faces, byte[] uprightNv21,
                           int uprightWidth, int uprightHeight, long frameStart) {
        long now = SystemClock.elapsedRealtime();
        final boolean stable = usesStablePersonIds();
        boolean doRecognition = false;
        if (stable) {
            // Must run every frame: clears per-frame anti-cross-talk claims and
            // prunes stale track bindings, even when feature extraction is skipped.
            resolver.beginFrame(now);
            doRecognition = now - lastFeatureMs >= FEATURE_INTERVAL_MS;
            if (doRecognition) {
                lastFeatureMs = now;
            }
            seenTracks.clear();
        }

        int detected = faces == null ? 0
                : Math.min(faces.detectedNum, FaceTrackingGlView.MAX_FACES);
        if (detected <= 0) {
            overlay.clearTracking();
            trackIdOverlay.clearTracking();
            if (stable) {
                labelByTrack.clear();
            }
            reportStats(frameStart);
            return;
        }

        int pointCount = 0;
        int boxCount = 0;
        int labelCount = 0;
        boxSmoother.beginFrame();
        for (int i = 0; i < detected; i++) {
            int sdkTrackId = faces.trackIds != null && i < faces.trackIds.length
                    ? faces.trackIds[i] : -1;
            int colorKey = sdkTrackId >= 0 ? sdkTrackId : Integer.MIN_VALUE + i;
            int color = colorForTrackId(colorKey);
            Point2f[] landmarks = faces.tokens != null && i < faces.tokens.length
                    && faces.tokens[i] != null
                    ? InspireFace.GetFaceDenseLandmarkFromFaceToken(faces.tokens[i]) : null;
            if (faces.rects != null && i < faces.rects.length && faces.rects[i] != null) {
                FaceRect rect = faces.rects[i];
                boxSmoother.smooth(colorKey, rect.x, rect.y,
                        rect.x + rect.width, rect.y + rect.height, smoothedRect);
                boxCount = appendBrackets(
                        boxVertices, boxCount, smoothedRect[0], smoothedRect[1],
                        smoothedRect[2], smoothedRect[3], color);

                if (stable) {
                    if (sdkTrackId >= 0) {
                        seenTracks.add(sdkTrackId);
                        // A bound track is cheap to keep alive: refresh TTL + claim,
                        // no native work. Otherwise resolve once the track is stable.
                        if (labelByTrack.containsKey(sdkTrackId)) {
                            resolver.touch(sdkTrackId, now);
                        } else if (doRecognition
                                && isStable(faces, i, rect, uprightWidth)) {
                            resolveFace(faces, i, sdkTrackId, session, stream, now);
                        }
                    }
                    if (labelCount < FaceTrackingGlView.MAX_FACES) {
                        String label = sdkTrackId >= 0
                                ? labelByTrack.get(sdkTrackId) : null;
                        labelTexts[labelCount] = label == null ? UNKNOWN_LABEL : label;
                        labelColors[labelCount] = color;
                        int offset = labelCount * 4;
                        labelRects[offset] = smoothedRect[0];
                        labelRects[offset + 1] = smoothedRect[1];
                        labelRects[offset + 2] = smoothedRect[2];
                        labelRects[offset + 3] = smoothedRect[3];
                        labelCount++;
                    }
                } else if (sdkTrackId >= 0
                        && labelCount < FaceTrackingGlView.MAX_FACES) {
                    // Legacy behaviour, unchanged.
                    labelTrackIds[labelCount] = sdkTrackId;
                    labelColors[labelCount] = color;
                    int offset = labelCount * 4;
                    labelRects[offset] = smoothedRect[0];
                    labelRects[offset + 1] = smoothedRect[1];
                    labelRects[offset + 2] = smoothedRect[2];
                    labelRects[offset + 3] = smoothedRect[3];
                    labelCount++;
                }
            }
            if (landmarks == null || landmarks.length == 0) {
                continue;
            }
            for (Point2f point : landmarks) {
                if (point == null || pointCount >= FaceTrackingGlView.MAX_FACES
                        * FaceTrackingGlView.LANDMARKS_PER_FACE) {
                    continue;
                }
                appendVertex(pointVertices, pointCount++, point.x, point.y, color);
            }
        }

        if (stable) {
            // Drop in-memory track bindings for faces that left; the gallery record stays.
            labelByTrack.keySet().retainAll(seenTracks);
        }

        overlay.submit(pointVertices, pointCount, boxVertices, boxCount,
                uprightWidth, uprightHeight, mirrored);
        if (stable) {
            trackIdOverlay.submitLabels(labelTexts, labelRects, labelColors, labelCount,
                    uprightWidth, uprightHeight, mirrored);
        } else {
            trackIdOverlay.submit(labelTrackIds, labelRects, labelColors, labelCount,
                    uprightWidth, uprightHeight, mirrored);
        }
        reportStats(frameStart);
    }

    @Override
    protected void onSessionError() {
        overlay.clearTracking();
        trackIdOverlay.clearTracking();
        boxSmoother.clear();
        if (usesStablePersonIds()) {
            labelByTrack.clear();
        }
        listener.onSessionError();
    }

    public void setMirrored(boolean mirrored) {
        this.mirrored = mirrored;
        overlay.clearTracking();
        trackIdOverlay.clearTracking();
        boxSmoother.clear();
    }

    public void clearTracking() {
        overlay.clearTracking();
        trackIdOverlay.clearTracking();
        boxSmoother.clear();
        if (usesStablePersonIds()) {
            labelByTrack.clear();
        }
    }

    /** Installs measured B0 thresholds; until calibrated the labelled defaults apply. */
    public void configureThresholds(float matchThreshold, float newThreshold) {
        if (usesStablePersonIds()) {
            resolver.configureThresholds(matchThreshold, newThreshold);
            Log.i(TAG, "THRESHOLDS match=" + matchThreshold + " new=" + newThreshold);
        }
    }

    /** Clears in-memory track bindings but keeps the persistent gallery (re-entry). */
    public void resetStableSession() {
        if (usesStablePersonIds()) {
            labelByTrack.clear();
            resolver.clear();
            lastFeatureMs = 0L;
            Log.i(TAG, "SESSION_RESET gallery_kept=true");
        }
    }

    private void resolveFace(MultipleFaceData faces, final int index, int trackId,
                             final Session session, final ImageStream stream, long now) {
        if (faces.tokens == null || index >= faces.tokens.length
                || faces.tokens[index] == null) {
            return;
        }
        FaceFeature feature = InspireFace.ExtractFaceFeature(
                session, stream, faces.tokens[index]);
        if (feature == null) {
            return;
        }
        // The stream is still alive here (base class wraps onFaces in
        // try-with-resources), so a lazy crop is only built when enrolling.
        EnrollContext context = new EnrollContext(
                () -> InspireFace.GetFaceAlignmentImage(session, stream, faces.tokens[index]));
        PersonIdResolver.Result result = resolver.resolve(trackId, feature, context, now);
        if (result.hasPersonId()) {
            labelByTrack.put(trackId, result.label());
            if (result.decision == PersonIdResolver.Decision.ENROLLED) {
                Log.i(TAG, "STABLE_ENROLLED track=" + trackId + " person=" + result.label());
            } else {
                Log.i(TAG, "STABLE_MATCHED track=" + trackId + " person=" + result.label()
                        + " conf=" + result.confidence);
            }
        } else if (result.decision == PersonIdResolver.Decision.ENROLL_FAILED) {
            Log.w(TAG, "STABLE_ENROLL_FAILED track=" + trackId);
        }
    }

    private boolean isStable(MultipleFaceData faces, int index, FaceRect rect,
                             int uprightWidth) {
        if (rect.width < uprightWidth * MIN_FACE_WIDTH_RATIO) {
            return false;
        }
        int count = faces.trackCounts != null && index < faces.trackCounts.length
                ? faces.trackCounts[index] : 0;
        return count >= MIN_TRACK_COUNT;
    }

    /** Lazily produces an aligned face crop, only when enrollment actually happens. */
    private interface CropSource {
        Bitmap make();
    }

    /** Opaque per-frame context passed from resolve() into Gallery.enroll(). */
    private static final class EnrollContext {
        final CropSource cropSource;

        EnrollContext(CropSource cropSource) {
            this.cropSource = cropSource;
        }
    }

    /** Adapter from the pure-Java resolver to FeatureHub / FaceRepository. */
    private final class GalleryAdapter
            implements PersonIdResolver.Gallery<FaceFeature, EnrollContext> {

        @Override
        public PersonIdResolver.Candidate bestMatch(FaceFeature feature) {
            FaceRepository.SearchResult result = repository.search(feature);
            // Ignore result.matched: the resolver owns the three-zone policy and
            // needs the raw cosine score, not the SDK's single hard threshold.
            if (result == null || result.record == null
                    || Float.isNaN(result.confidence)) {
                return new PersonIdResolver.Candidate(-1L, Float.NEGATIVE_INFINITY);
            }
            return new PersonIdResolver.Candidate(result.record.id, result.confidence);
        }

        @Override
        public long enroll(FaceFeature feature, EnrollContext context) {
            Bitmap crop = context.cropSource.make();
            if (crop == null || crop.isRecycled()) {
                return -1L;
            }
            try {
                FaceRepository.InsertResult inserted =
                        repository.insert("Auto", feature, crop);
                return inserted.success && inserted.record != null
                        ? inserted.record.id : -1L;
            } finally {
                // insert() synchronously compresses the crop into a staged file.
                if (!crop.isRecycled()) {
                    crop.recycle();
                }
            }
        }
    }

    private void reportStats(long frameStart) {
        long now = SystemClock.elapsedRealtime();
        long latency = Math.max(0L, now - frameStart);
        latencyEmaMs = latencyEmaMs == 0.0
                ? latency : latencyEmaMs * 0.85 + latency * 0.15;
        fpsWindowFrames++;
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now;
        } else if (now - fpsWindowStart >= 1_000L) {
            fps = fpsWindowFrames * 1_000.0 / (now - fpsWindowStart);
            fpsWindowFrames = 0;
            fpsWindowStart = now;
        }
        if (now - lastReport >= REPORT_INTERVAL_MS) {
            lastReport = now;
            listener.onStats(fps, Math.round(latencyEmaMs));
        }
    }

    /** Keeps a Track ID's color stable for the lifetime of this camera Session. */
    private int colorForTrackId(int trackId) {
        int existing = colorByTrackId.indexOfKey(trackId);
        if (existing >= 0) {
            return colorByTrackId.valueAt(existing);
        }
        int color;
        int attempts = 0;
        do {
            color = colorForOrdinal(nextColorOrdinal++);
            attempts++;
        } while (colorByTrackId.indexOfValue(color) >= 0 && attempts < 720);
        colorByTrackId.put(trackId, color);
        return color;
    }

    private static int colorForOrdinal(int ordinal) {
        if (ordinal < TRACK_COLORS.length) {
            return TRACK_COLORS[ordinal];
        }
        // The golden-angle sequence keeps later IDs visually separated without a small palette.
        float hue = (17f + ordinal * 137.50776f) % 360f;
        float saturation = 0.68f + (ordinal % 3) * 0.08f;
        return Color.HSVToColor(new float[]{hue, saturation, 1f});
    }

    private static int appendBrackets(float[] target, int vertexCount,
                                      float left, float top, float right, float bottom,
                                      int color) {
        float length = Math.min(right - left, bottom - top) * 0.22f;

        vertexCount = appendLine(target, vertexCount,
                left, top + length, left, top, color);
        vertexCount = appendLine(target, vertexCount,
                left, top, left + length, top, color);
        vertexCount = appendLine(target, vertexCount,
                right - length, top, right, top, color);
        vertexCount = appendLine(target, vertexCount,
                right, top, right, top + length, color);
        vertexCount = appendLine(target, vertexCount,
                left, bottom - length, left, bottom, color);
        vertexCount = appendLine(target, vertexCount,
                left, bottom, left + length, bottom, color);
        vertexCount = appendLine(target, vertexCount,
                right - length, bottom, right, bottom, color);
        vertexCount = appendLine(target, vertexCount,
                right, bottom, right, bottom - length, color);
        return vertexCount;
    }

    private static int appendLine(float[] target, int vertexCount,
                                  float x0, float y0, float x1, float y1, int color) {
        appendVertex(target, vertexCount++, x0, y0, color);
        appendVertex(target, vertexCount++, x1, y1, color);
        return vertexCount;
    }

    private static void appendVertex(float[] target, int vertexIndex,
                                     float x, float y, int color) {
        int offset = vertexIndex * FaceTrackingGlView.FLOATS_PER_VERTEX;
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = Color.red(color) / 255f;
        target[offset + 3] = Color.green(color) / 255f;
        target[offset + 4] = Color.blue(color) / 255f;
        target[offset + 5] = Color.alpha(color) / 255f;
    }
}
