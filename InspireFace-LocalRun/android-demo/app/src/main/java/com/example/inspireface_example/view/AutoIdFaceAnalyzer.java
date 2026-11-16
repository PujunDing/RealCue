package com.example.inspireface_example.view;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.inspireface_example.face.FaceRepository;
import com.example.inspireface_example.face.PersonIdResolver;
import com.insightface.sdk.inspireface.InspireFace;
import com.insightface.sdk.inspireface.base.FaceFeature;
import com.insightface.sdk.inspireface.base.FaceRect;
import com.insightface.sdk.inspireface.base.ImageStream;
import com.insightface.sdk.inspireface.base.MultipleFaceData;
import com.insightface.sdk.inspireface.base.Session;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Multi-face live pipeline that assigns persistent gallery ids (P001, P002, ...)
 * instead of short-lived SDK track ids.
 *
 * <p>Extends {@link UprightFaceCameraAnalyzer}, so the Session, ImageStream and all
 * FeatureHub calls stay on the single CameraX analysis executor. FeatureHub is a
 * process-wide singleton guarded by {@code FaceRepository.HUB_LOCK}; this analyzer
 * never switches threads.
 *
 * <p>Policy lives in {@link PersonIdResolver} (pure Java, unit-tested). This class
 * is the adapter: native feature extraction, gallery search/enrollment and overlay
 * rendering.
 *
 * <p><b>Known limitation (honest, not a bug in the resolver):</b>
 * {@link FaceRepository#open()} configures FeatureHub with the SDK's recommended
 * search threshold, so a low-similarity candidate is filtered out before we see it.
 * In practice the resolver therefore sees either a high-confidence hit or "no
 * candidate", and the gray zone is not reachable until B0 calibration lets us lower
 * the FeatureHub threshold. The consecutive-miss rule still prevents a new identity
 * from being created on a single bad frame.
 */
public final class AutoIdFaceAnalyzer extends UprightFaceCameraAnalyzer {

    private static final String TAG = "RealCueAutoId";
    private static final int MAX_FACES = FaceTrackingGlView.MAX_FACES;
    private static final long FEATURE_INTERVAL_MS = 320L;
    private static final long REPORT_INTERVAL_MS = 300L;
    private static final int MIN_TRACK_COUNT = 3;
    private static final float MIN_FACE_WIDTH_RATIO = 0.12f;
    private static final String UNKNOWN_LABEL = "…";

    private static final int[] TRACK_COLORS = {
            0xFF00E5A0, 0xFFFFC400, 0xFF40C4FF, 0xFFFF6E9C, 0xFFC6A5FF,
            0xFFFF8A65, 0xFF76FF03, 0xFF18FFFF, 0xFFFFD180, 0xFFEA80FC
    };

    public interface Listener {
        void onSessionReady();

        void onSessionError();

        /** Throttled callback, still on the analysis executor. */
        void onUpdate(int faceCount, long gallerySize, String[] labels, String event);
    }

    /** Lazily produces an aligned face crop, only when enrollment actually happens. */
    public interface CropSource {
        Bitmap make();
    }

    /** Opaque per-frame context passed from resolve() into Gallery.enroll(). */
    public static final class EnrollContext {
        final CropSource cropSource;

        EnrollContext(CropSource cropSource) {
            this.cropSource = cropSource;
        }
    }

    private final FaceTrackingGlView overlay;
    private final TrackIdOverlayView labelOverlay;
    private final FaceRepository repository;
    private final Listener listener;
    private final PersonIdResolver<FaceFeature, EnrollContext> resolver;

    private final TrackBoxSmoother boxSmoother = new TrackBoxSmoother();
    private final float[] smoothedRect = new float[4];
    private final float[] emptyPoints = new float[0];
    private final float[] boxVertices = new float[
            MAX_FACES * FaceTrackingGlView.BOX_VERTICES_PER_FACE
                    * FaceTrackingGlView.FLOATS_PER_VERTEX];
    private final String[] labelTexts = new String[MAX_FACES];
    private final int[] labelColors = new int[MAX_FACES];
    private final float[] labelRects = new float[MAX_FACES * 4];
    private final Map<Integer, Integer> colorByTrack = new HashMap<>();
    private final Map<Integer, String> labelByTrack = new HashMap<>();
    private final Set<Integer> seenTracks = new HashSet<>();

    private int nextColorOrdinal;
    private volatile boolean mirrored = true;
    private volatile boolean resetRequested;
    private long lastFeatureMs;
    private long lastReportMs;
    private String lastEvent = "等待人脸";

    public AutoIdFaceAnalyzer(FaceTrackingGlView overlay,
                              TrackIdOverlayView labelOverlay,
                              FaceRepository repository,
                              Listener listener) {
        this.overlay = overlay;
        this.labelOverlay = labelOverlay;
        this.repository = repository;
        this.listener = listener;
        this.resolver = new PersonIdResolver<>(new GalleryAdapter());
    }

    @Override
    protected Session createSession() {
        return FaceEngine.createAutoIdVideoSession(MAX_FACES);
    }

    @Override
    protected void beforeFrame() {
        if (resetRequested) {
            resetRequested = false;
            // Memory state only: the persistent gallery survives a session reset,
            // which is what "leave and come back keeps P001" relies on.
            resolver.clear();
            labelByTrack.clear();
            colorByTrack.clear();
            boxSmoother.clear();
            lastFeatureMs = 0L;
            lastEvent = "会话已重置（人物库保留）";
            Log.i(TAG, "SESSION_RESET gallery_kept=true");
        }
    }

    @Override
    protected void onSessionReady() {
        Log.i(TAG, "SESSION_READY match=" + resolver.matchThreshold()
                + " new=" + resolver.newThreshold() + " maxFaces=" + MAX_FACES);
        listener.onSessionReady();
    }

    @Override
    protected void onFaces(Session session, ImageStream stream,
                           @Nullable MultipleFaceData faces, byte[] uprightNv21,
                           int uprightWidth, int uprightHeight, long frameStart) {
        long now = SystemClock.elapsedRealtime();
        // Must run every frame: clears per-frame anti-cross-talk claims and prunes
        // stale bindings, even on frames where we skip native feature extraction.
        resolver.beginFrame(now);
        boolean doRecognition = now - lastFeatureMs >= FEATURE_INTERVAL_MS;
        if (doRecognition) {
            lastFeatureMs = now;
        }

        int detected = faces == null ? 0 : Math.min(faces.detectedNum, MAX_FACES);
        if (detected <= 0) {
            overlay.clearTracking();
            labelOverlay.clearTracking();
            labelByTrack.clear();
            report(0, 0, now);
            return;
        }

        int boxCount = 0;
        int labelCount = 0;
        seenTracks.clear();
        boxSmoother.beginFrame();

        for (int i = 0; i < detected; i++) {
            int trackId = trackIdOf(faces, i);
            int colorKey = trackId >= 0 ? trackId : Integer.MIN_VALUE + i;
            int color = colorForTrack(colorKey);
            FaceRect rect = rectOf(faces, i);
            if (rect == null) {
                continue;
            }
            boxSmoother.smooth(colorKey, rect.x, rect.y,
                    rect.x + rect.width, rect.y + rect.height, smoothedRect);
            boxCount = appendBrackets(boxVertices, boxCount,
                    smoothedRect[0], smoothedRect[1], smoothedRect[2], smoothedRect[3], color);

            if (trackId >= 0) {
                seenTracks.add(trackId);
            }

            // A bound track is cheap to keep alive: refresh TTL + claim, no native work.
            if (trackId >= 0 && labelByTrack.containsKey(trackId)) {
                resolver.touch(trackId, now);
            } else if (doRecognition && trackId >= 0
                    && isStable(faces, i, rect, uprightWidth)) {
                resolveFace(faces, i, trackId, session, stream, now);
            }

            String label = trackId >= 0 ? labelByTrack.get(trackId) : null;
            if (labelCount < MAX_FACES) {
                labelTexts[labelCount] = label == null ? UNKNOWN_LABEL : label;
                labelColors[labelCount] = color;
                int offset = labelCount * 4;
                labelRects[offset] = smoothedRect[0];
                labelRects[offset + 1] = smoothedRect[1];
                labelRects[offset + 2] = smoothedRect[2];
                labelRects[offset + 3] = smoothedRect[3];
                labelCount++;
            }
        }

        // Drop labels for tracks that left the frame; the gallery record stays.
        labelByTrack.keySet().retainAll(seenTracks);

        overlay.submit(emptyPoints, 0, boxVertices, boxCount,
                uprightWidth, uprightHeight, mirrored);
        labelOverlay.submitLabels(labelTexts, labelRects, labelColors, labelCount,
                uprightWidth, uprightHeight, mirrored);
        report(detected, labelCount, now);
    }

    @Override
    protected void onSessionError() {
        overlay.clearTracking();
        labelOverlay.clearTracking();
        labelByTrack.clear();
        boxSmoother.clear();
        listener.onSessionError();
    }

    /** Drops in-memory track bindings but keeps the gallery; used for re-entry tests. */
    public void resetSession() {
        resetRequested = true;
    }

    public void setMirrored(boolean mirrored) {
        this.mirrored = mirrored;
        overlay.clearTracking();
        labelOverlay.clearTracking();
        boxSmoother.clear();
    }

    public void clearTracking() {
        overlay.clearTracking();
        labelOverlay.clearTracking();
        boxSmoother.clear();
    }

    /**
     * Installs measured B0 thresholds. Until two real subjects are calibrated this
     * is not called and the resolver keeps its clearly-labelled heuristic defaults.
     */
    public void configureThresholds(float matchThreshold, float newThreshold) {
        resolver.configureThresholds(matchThreshold, newThreshold);
        Log.i(TAG, "THRESHOLDS match=" + matchThreshold + " new=" + newThreshold);
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
        // The stream is still alive here (the base class wraps onFaces in
        // try-with-resources), so a lazy crop source is safe and avoids per-frame
        // alignment work for faces that are never enrolled.
        EnrollContext context = new EnrollContext(
                () -> InspireFace.GetFaceAlignmentImage(session, stream, faces.tokens[index]));
        PersonIdResolver.Result result = resolver.resolve(trackId, feature, context, now);
        if (result.hasPersonId()) {
            labelByTrack.put(trackId, result.label());
            if (result.decision == PersonIdResolver.Decision.ENROLLED) {
                lastEvent = "新建 " + result.label();
                Log.i(TAG, "ENROLLED track=" + trackId + " person=" + result.label());
            } else {
                lastEvent = "命中 " + result.label();
                Log.i(TAG, "MATCHED track=" + trackId + " person=" + result.label()
                        + " conf=" + result.confidence);
            }
        } else if (result.decision == PersonIdResolver.Decision.ENROLL_FAILED) {
            lastEvent = "建档失败，重试中";
            Log.w(TAG, "ENROLL_FAILED track=" + trackId);
        } else if (result.decision == PersonIdResolver.Decision.PENDING) {
            lastEvent = "采集中…";
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

    private void report(int faceCount, int labelCount, long now) {
        if (now - lastReportMs < REPORT_INTERVAL_MS) {
            return;
        }
        lastReportMs = now;
        String[] snapshot = new String[labelCount];
        System.arraycopy(labelTexts, 0, snapshot, 0, labelCount);
        listener.onUpdate(faceCount, repository.query(null).size(), snapshot, lastEvent);
    }

    /** Adapter from the pure-Java resolver to FeatureHub / FaceRepository. */
    private final class GalleryAdapter
            implements PersonIdResolver.Gallery<FaceFeature, EnrollContext> {

        @Override
        public PersonIdResolver.Candidate bestMatch(FaceFeature feature) {
            FaceRepository.SearchResult result = repository.search(feature);
            // Ignore result.matched: it applies the SDK's single hard threshold.
            // The resolver owns the three-zone policy and needs the raw score.
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
                // insert() synchronously compresses the crop into a staged file,
                // so releasing the bitmap afterwards is safe.
                if (!crop.isRecycled()) {
                    crop.recycle();
                }
            }
        }
    }

    private static int trackIdOf(MultipleFaceData faces, int index) {
        return faces.trackIds != null && index < faces.trackIds.length
                ? faces.trackIds[index] : -1;
    }

    @Nullable
    private static FaceRect rectOf(MultipleFaceData faces, int index) {
        return faces.rects != null && index < faces.rects.length
                ? faces.rects[index] : null;
    }

    private int colorForTrack(int trackId) {
        Integer existing = colorByTrack.get(trackId);
        if (existing != null) {
            return existing;
        }
        int color = colorForOrdinal(nextColorOrdinal++);
        colorByTrack.put(trackId, color);
        return color;
    }

    private static int colorForOrdinal(int ordinal) {
        if (ordinal >= 0 && ordinal < TRACK_COLORS.length) {
            return TRACK_COLORS[ordinal];
        }
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
