package com.example.inspireface_example.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.insightface.sdk.inspireface.InspireFace;
import com.insightface.sdk.inspireface.base.FaceFeature;
import com.insightface.sdk.inspireface.base.ImageStream;
import com.insightface.sdk.inspireface.base.MultipleFaceData;
import com.insightface.sdk.inspireface.base.Session;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Project-added B0 threshold calibration (not upstream Demo code).
 *
 * Gap this closes: the earlier GalleryAccuracyProbeTest only measured
 * intra-person stability with a single bundled face. Whether the system is
 * actually "accurate" depends on the separation between same-person scores and
 * different-person scores, which needs two real people on a physical phone.
 *
 * Three layers, all asserted every run (no untested branches):
 *  1. statisticsLogicIsCorrect - pure arithmetic, no native model needed.
 *  2. builtInSampleProducesValidIntraPersonScores - real feature extraction on
 *     the bundled sample, verifies the same-person score pipeline end to end.
 *  3. calibrationPipelineWritesResultFile - full pipeline (read images from
 *     the app's private external dir -> features -> A/A, B/B, A/B score matrices
 *     -> statistics -> JSON + CSV on disk -> parse back). When no real samples
 *     were pushed, it generates a clearly-flagged synthetic dry-run set under
 *     _dryrun/ so the very same code path is still exercised and asserted.
 *
 * Push real samples with: python runtime/calibrate_threshold.py <dir-with-A-and-B>
 */
@RunWith(AndroidJUnit4.class)
public final class GalleryThresholdCalibrationTest {

    private static final String TAG = "RealCueCalib";
    private static final String MODEL = "Pikachu";
    private static final String CALIB_DIR = "calibration";
    private static final String REAL_A = "A";
    private static final String REAL_B = "B";
    private static final String DRY_DIR = "_dryrun";
    private static final String RESULT_JSON = "threshold-result.json";
    private static final String RESULT_CSV = "threshold-pairs.csv";
    private static final int MIN_FEATURES_PER_PERSON = 2;

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before public void prepare() throws Exception { SdkTestRuntime.resetWhenIdle(); }
    @After public void release() throws Exception { SdkTestRuntime.resetWhenIdle(); }

    /** Pure arithmetic: separable, overlapping and missing-negative-sample cases. */
    @Test
    public void statisticsLogicIsCorrect() {
        List<Float> same = Arrays.asList(0.92f, 0.95f, 0.88f, 0.97f, 0.90f);
        List<Float> diff = Arrays.asList(0.31f, 0.42f, 0.28f, 0.55f, 0.38f);
        CalibrationMath.Result r = CalibrationMath.analyze(same, diff);

        assertEquals(5, r.sameCount);
        assertEquals(5, r.diffCount);
        assertEquals(0.88f, r.sameMin, 0.0001f);
        assertEquals(0.97f, r.sameMax, 0.0001f);
        assertEquals(0.55f, r.diffMax, 0.0001f);
        assertTrue(r.hasDiffData);
        assertTrue("0.88 > 0.55 must be separable", r.separable);
        assertEquals(0.33f, r.margin, 0.0001f);
        assertEquals(0.715f, r.matchThreshold, 0.0001f);
        assertEquals(0.595f, r.newThreshold, 0.0001f);
        assertEquals(1.0f, r.recallAtMatch, 0.0001f);
        assertEquals(0.0f, r.falseAcceptAtMatch, 0.0001f);
        Log.i(TAG, "MATH separable case: gap midpoint = " + r.matchThreshold);

        List<Float> overlapSame = Arrays.asList(0.55f, 0.60f, 0.72f, 0.80f);
        List<Float> overlapDiff = Arrays.asList(0.58f, 0.65f, 0.70f);
        CalibrationMath.Result o = CalibrationMath.analyze(overlapSame, overlapDiff);
        assertFalse("overlapping distributions must not be marked separable",
                o.separable);
        assertTrue("negative margin expected", o.margin < 0f);
        assertEquals("zero false accepts is prioritised", 0.0f,
                o.falseAcceptAtMatch, 0.0001f);
        assertEquals("recall must be reported honestly", 0.5f,
                o.recallAtMatch, 0.0001f);
        Log.i(TAG, "MATH overlap case: recall=" + o.recallAtMatch
                + " at T_match=" + o.matchThreshold);

        CalibrationMath.Result n = CalibrationMath.analyze(
                Arrays.asList(0.90f, 0.80f), Collections.<Float>emptyList());
        assertFalse(n.hasDiffData);
        assertFalse(n.separable);
        assertTrue("without negatives only recall can be guaranteed",
                n.matchThreshold < n.sameMin);
        Log.i(TAG, "MATH missing-negatives case: T_match=" + n.matchThreshold
                + " (flagged unusable)");

        assertEquals(0.38f, CalibrationMath.percentile(
                Arrays.asList(0.28f, 0.31f, 0.38f, 0.42f, 0.55f), 0.5f), 0.0001f);
        assertEquals(0.7f, CalibrationMath.percentile(
                Arrays.asList(0.7f), 0.95f), 0.0001f);
        try {
            CalibrationMath.analyze(Collections.<Float>emptyList(), diff);
            fail("empty same-person scores must be rejected");
        } catch (IllegalArgumentException expected) {
            Log.i(TAG, "MATH empty-input guard OK");
        }
    }

    /** Real native features: same-person pairs on the bundled face. */
    @Test
    public void builtInSampleProducesValidIntraPersonScores() throws Exception {
        Bitmap base = loadSampleFace();
        Session session = null;
        Map<String, Bitmap> variants = new LinkedHashMap<>();
        try {
            assertTrue("GlobalLaunch " + MODEL,
                    InspireFace.GlobalLaunch(context, MODEL));
            session = FaceEngine.createRecognitionSession();
            assertNotNull("recognition session", session);

            float official = InspireFace.GetRecommendedCosineThreshold();
            Log.i(TAG, "=== INTRA official=" + official
                    + " featureLength=" + InspireFace.GetFeatureLength() + " ===");
            assertTrue("official threshold must be within (0,1)",
                    official > 0f && official < 1f);

            variants.put("base", base);
            variants.put("jpeg_q60", jpegRoundTrip(base, 60));
            variants.put("bright_+40", brightness(base, 40f));
            variants.put("dark_-40", brightness(base, -40f));
            variants.put("scale_0.75", scaled(base, 0.75f));
            variants.put("mirror", mirrored(base));

            List<FaceFeature> features = new ArrayList<>();
            List<String> names = new ArrayList<>();
            int noFace = 0;
            for (Map.Entry<String, Bitmap> entry : variants.entrySet()) {
                Bitmap bitmap = entry.getValue();
                if (bitmap == null) {
                    noFace++;
                    continue;
                }
                FaceFeature feature = extract(session, bitmap);
                if (feature == null) {
                    noFace++;
                    Log.i(TAG, "INTRA " + entry.getKey() + " NO_FACE");
                } else {
                    features.add(feature);
                    names.add(entry.getKey());
                }
            }
            assertTrue("most variants must yield a feature",
                    features.size() >= 5);
            assertEquals("every generated variant must yield a face",
                    0, noFace);

            Bitmap blank = Bitmap.createBitmap(base.getWidth(), base.getHeight(),
                    Bitmap.Config.ARGB_8888);
            assertTrue("blank frame must be NO_FACE", extract(session, blank) == null);
            blank.recycle();

            List<Float> sameScores = new ArrayList<>();
            for (int i = 0; i < features.size(); i++) {
                for (int j = i + 1; j < features.size(); j++) {
                    float score = InspireFace.FaceComparison(
                            features.get(i), features.get(j));
                    sameScores.add(score);
                    Log.i(TAG, String.format(Locale.ROOT,
                            "INTRA %s|%s=%.4f", names.get(i), names.get(j), score));
                }
            }
            assertEquals("every unordered variant pair must be scored",
                    features.size() * (features.size() - 1) / 2, sameScores.size());
            float min = Collections.min(sameScores);
            Log.i(TAG, "INTRA min=" + min + " pairs=" + sameScores.size()
                    + " official=" + official);
            assertTrue("same-person scores must clear the official threshold",
                    min > official);
            assertTrue("same-person scores must stay high", min > 0.90f);

            CalibrationMath.Result r = CalibrationMath.analyze(
                    sameScores, Collections.<Float>emptyList());
            assertEquals(min, r.sameMin, 0.0001f);
            assertFalse("no different-person samples were supplied", r.hasDiffData);
            Log.i(TAG, "INTRA falseRejectRiskAtOfficial="
                    + (official > r.sameMin) + " falseAcceptRiskUnknown=true");
        } finally {
            if (session != null) {
                FaceEngine.releaseSession(session);
            }
            if (InspireFace.QueryLaunchStatus()) {
                InspireFace.GlobalTerminate();
            }
            for (Map.Entry<String, Bitmap> entry : variants.entrySet()) {
                Bitmap b = entry.getValue();
                if (b != null && b != base && !b.isRecycled()) {
                    b.recycle();
                }
            }
            base.recycle();
        }
    }

    /**
     * End-to-end calibration pipeline. Uses real A/ and B/ folders if present,
     * otherwise generates a clearly flagged synthetic dry-run set so the whole
     * pipeline (including disk I/O) is asserted on every run.
     */
    @Test
    public void calibrationPipelineWritesResultFile() throws Exception {
        File root = context.getExternalFilesDir(CALIB_DIR);
        if (root == null) {
            root = new File(context.getFilesDir(), CALIB_DIR);
            Log.w(TAG, "external files dir unavailable, falling back to internal");
        }
        if (!root.exists()) {
            assertTrue("create calibration dir", root.mkdirs() || root.isDirectory());
        }

        List<File> filesA = images(new File(root, REAL_A));
        List<File> filesB = images(new File(root, REAL_B));
        boolean external = filesA.size() >= MIN_FEATURES_PER_PERSON
                && filesB.size() >= MIN_FEATURES_PER_PERSON;
        String mode = "external_samples";
        if (!external) {
            File dry = new File(root, DRY_DIR);
            writeDryRunSamples(dry);
            filesA = images(new File(dry, REAL_A));
            filesB = images(new File(dry, REAL_B));
            mode = "synthetic_dry_run";
            Log.i(TAG, "NO REAL SAMPLES - generated synthetic dry-run set");
        }
        assertTrue("need >=2 images per person",
                filesA.size() >= MIN_FEATURES_PER_PERSON);
        assertTrue("need >=2 images per person",
                filesB.size() >= MIN_FEATURES_PER_PERSON);
        Log.i(TAG, "=== B0 CALIBRATION mode=" + mode + " A=" + filesA.size()
                + " B=" + filesB.size() + " dir=" + root.getAbsolutePath() + " ===");

        Session session = null;
        List<Bitmap> loadedBitmaps = new ArrayList<>();
        try {
            assertTrue("GlobalLaunch " + MODEL,
                    InspireFace.GlobalLaunch(context, MODEL));
            session = FaceEngine.createRecognitionSession();
            assertNotNull("recognition session", session);
            float official = InspireFace.GetRecommendedCosineThreshold();

            List<FaceFeature> featuresA = new ArrayList<>();
            List<FaceFeature> featuresB = new ArrayList<>();
            int[] noFace = new int[2];
            for (File f : filesA) {
                FaceFeature feature = featureFromFile(session, f, loadedBitmaps);
                if (feature == null) { noFace[0]++; } else { featuresA.add(feature); }
            }
            for (File f : filesB) {
                FaceFeature feature = featureFromFile(session, f, loadedBitmaps);
                if (feature == null) { noFace[1]++; } else { featuresB.add(feature); }
            }
            assertTrue("person A must yield >=2 usable features",
                    featuresA.size() >= MIN_FEATURES_PER_PERSON);
            assertTrue("person B must yield >=2 usable features",
                    featuresB.size() >= MIN_FEATURES_PER_PERSON);

            List<String> csv = new ArrayList<>();
            csv.add("group,pair,score");
            List<Float> sameScores = new ArrayList<>();
            List<Float> diffScores = new ArrayList<>();
            for (int i = 0; i < featuresA.size(); i++) {
                for (int j = i + 1; j < featuresA.size(); j++) {
                    float s = InspireFace.FaceComparison(
                            featuresA.get(i), featuresA.get(j));
                    sameScores.add(s);
                    csv.add(String.format(Locale.ROOT, "same,A#%d|A#%d,%.4f", i, j, s));
                }
            }
            for (int i = 0; i < featuresB.size(); i++) {
                for (int j = i + 1; j < featuresB.size(); j++) {
                    float s = InspireFace.FaceComparison(
                            featuresB.get(i), featuresB.get(j));
                    sameScores.add(s);
                    csv.add(String.format(Locale.ROOT, "same,B#%d|B#%d,%.4f", i, j, s));
                }
            }
            for (int i = 0; i < featuresA.size(); i++) {
                for (int j = 0; j < featuresB.size(); j++) {
                    float s = InspireFace.FaceComparison(
                            featuresA.get(i), featuresB.get(j));
                    diffScores.add(s);
                    csv.add(String.format(Locale.ROOT, "diff,A#%d|B#%d,%.4f", i, j, s));
                }
            }
            assertFalse("same-person pairs must exist", sameScores.isEmpty());
            assertFalse("different-person pairs must exist", diffScores.isEmpty());

            CalibrationMath.Result r = CalibrationMath.analyze(sameScores, diffScores);
            boolean faRisk = official < r.diffMax;
            boolean frRisk = official > r.sameMin;
            // Guard against the most damaging operator error: putting the
            // same person in both folders. Their cross scores would sit as
            // high as the same-person scores, which must never be used to
            // derive thresholds.
            boolean looksLikeSamePerson = external && !r.separable
                    && r.diffMax >= (r.sameMin - 0.02f);
            boolean unusable = !external || looksLikeSamePerson;
            String warning;
            if (!external) {
                warning = "SYNTHETIC DRY RUN - both folders contain the SAME bundled face. "
                        + "These numbers only prove the pipeline works. "
                        + "Push two real people into A/ and B/ before using any threshold.";
            } else if (looksLikeSamePerson) {
                warning = "A/ and B/ look like the SAME PERSON (different-person "
                        + "scores are as high as same-person scores). Put two genuinely "
                        + "different people in the two folders. Thresholds NOT usable.";
            } else if (r.separable) {
                warning = "Distributions are separable; T_match/T_new are usable.";
            } else {
                warning = "Distributions OVERLAP - no threshold is both fully "
                        + "sensitive and specific; review recall/FAR before use.";
            }

            String json = buildResultJson(mode, r, official, featuresA.size(),
                    featuresB.size(), noFace[0], noFace[1], root, warning,
                    faRisk, frRisk, unusable);
            File jsonFile = new File(root, RESULT_JSON);
            File csvFile = new File(root, RESULT_CSV);
            writeText(jsonFile, json);
            writeText(csvFile, join(csv, "\n") + "\n");

            JSONObject parsed = new JSONObject(readText(jsonFile));
            assertEquals(mode, parsed.getString("mode"));
            assertEquals(r.sameCount, parsed.getJSONObject("stats").getInt("sameCount"));
            assertEquals(r.diffCount, parsed.getJSONObject("stats").getInt("diffCount"));
            assertEquals(unusable, parsed.getBoolean("doNotUseForThreshold"));
            assertTrue("CSV must contain every pair plus header",
                    readText(csvFile).split("\n").length
                            == sameScores.size() + diffScores.size() + 1);

            Log.i(TAG, "official=" + official + " sameMin=" + r.sameMin
                    + " sameMax=" + r.sameMax + " diffMax=" + r.diffMax);
            Log.i(TAG, "separable=" + r.separable + " margin=" + r.margin
                    + " T_match=" + r.matchThreshold + " T_new=" + r.newThreshold);
            Log.i(TAG, "recallAtMatch=" + r.recallAtMatch
                    + " falseAcceptAtMatch=" + r.falseAcceptAtMatch);
            Log.i(TAG, "RISK falseAcceptAtOfficial=" + faRisk
                    + " falseRejectAtOfficial=" + frRisk);
            Log.i(TAG, "GUARD looksLikeSamePerson=" + looksLikeSamePerson
                    + " unusable=" + unusable);
            Log.i(TAG, "WARNING " + warning);
            // Mirror the artifacts into logcat. AGP tears down the app's
            // external files dir after connectedAndroidTest, so adb pull
            // alone cannot be relied on. The host script reassembles these
            // lines when the files are gone.
            Log.i(TAG, "RESULT_JSON_BEGIN");
            for (String line : json.split("\n")) {
                Log.i(TAG, "JSON|" + line);
            }
            Log.i(TAG, "RESULT_JSON_END");
            Log.i(TAG, "RESULT_CSV_BEGIN");
            for (String line : csv) {
                Log.i(TAG, "CSV|" + line);
            }
            Log.i(TAG, "RESULT_CSV_END");
            Log.i(TAG, "RESULT_JSON=" + jsonFile.getAbsolutePath());
            Log.i(TAG, "RESULT_CSV=" + csvFile.getAbsolutePath());
            Log.i(TAG, "=== B0 CALIBRATION DONE ===");
        } finally {
            if (session != null) {
                FaceEngine.releaseSession(session);
            }
            if (InspireFace.QueryLaunchStatus()) {
                InspireFace.GlobalTerminate();
            }
            for (Bitmap bitmap : loadedBitmaps) {
                if (!bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }
    }

    private static String buildResultJson(String mode, CalibrationMath.Result r,
                                          float official, int countA, int countB,
                                          int noFaceA, int noFaceB, File root,
                                          String warning, boolean faRisk,
                                          boolean frRisk, boolean unusable) {
        return String.format(Locale.ROOT,
                "{\n"
                        + "  \"mode\": \"%s\",\n"
                        + "  \"model\": \"%s\",\n"
                        + "  \"officialThreshold\": %.4f,\n"
                        + "  \"featureLength\": %d,\n"
                        + "  \"sampleCountA\": %d,\n"
                        + "  \"sampleCountB\": %d,\n"
                        + "  \"noFaceA\": %d,\n"
                        + "  \"noFaceB\": %d,\n"
                        + "  \"doNotUseForThreshold\": %b,\n"
                        + "  \"falseAcceptAtOfficial\": %b,\n"
                        + "  \"falseRejectAtOfficial\": %b,\n"
                        + "  \"resultDir\": \"%s\",\n"
                        + "  \"warning\": \"%s\",\n"
                        + "  \"stats\": %s\n"
                        + "}\n",
                mode, MODEL, official, InspireFace.GetFeatureLength(),
                countA, countB, noFaceA, noFaceB,
                unusable, faRisk, frRisk,
                root.getAbsolutePath(), warning.replace("\"", "'"), r.asJson());
    }

    private void writeDryRunSamples(File dry) throws Exception {
        File dirA = new File(dry, REAL_A);
        File dirB = new File(dry, REAL_B);
        assertTrue("mkdir dry A", dirA.mkdirs() || dirA.isDirectory());
        assertTrue("mkdir dry B", dirB.mkdirs() || dirB.isDirectory());
        Bitmap base = loadSampleFace();
        LinkedHashMap<String, Bitmap> setA = new LinkedHashMap<>();
        LinkedHashMap<String, Bitmap> setB = new LinkedHashMap<>();
        setA.put("a0_base", base);
        setA.put("a1_jpeg", jpegRoundTrip(base, 70));
        setA.put("a2_bright", brightness(base, 25f));
        setB.put("b0_dark", brightness(base, -25f));
        setB.put("b1_scale", scaled(base, 0.85f));
        setB.put("b2_mirror", mirrored(base));
        int index = 0;
        for (Map.Entry<String, Bitmap> e : setA.entrySet()) {
            writeJpeg(new File(dirA,
                    String.format(Locale.ROOT, "sample_%02d.jpg", index)),
                    e.getValue(), 85);
            index++;
        }
        index = 0;
        for (Map.Entry<String, Bitmap> e : setB.entrySet()) {
            writeJpeg(new File(dirB,
                    String.format(Locale.ROOT, "sample_%02d.jpg", index)),
                    e.getValue(), 85);
            index++;
        }
        for (Bitmap b : setA.values()) {
            if (b != null && b != base && !b.isRecycled()) { b.recycle(); }
        }
        for (Bitmap b : setB.values()) {
            if (b != null && b != base && !b.isRecycled()) { b.recycle(); }
        }
        base.recycle();
    }

    private static void writeJpeg(File file, Bitmap bitmap, int quality)
            throws Exception {
        if (bitmap == null) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer);
            out.write(buffer.toByteArray());
        }
    }

    private static FaceFeature featureFromFile(Session session, File file,
                                               List<Bitmap> keepAlive) {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) {
            Log.w(TAG, "DECODE_FAILED " + file.getName());
            return null;
        }
        keepAlive.add(bitmap);
        return extract(session, bitmap);
    }

    private static FaceFeature extract(Session session, Bitmap bitmap) {
        ImageStream stream = InspireFace.CreateImageStreamFromBitmap(
                bitmap, InspireFace.CAMERA_ROTATION_0);
        if (stream == null) {
            return null;
        }
        try {
            MultipleFaceData faces = InspireFace.ExecuteFaceTrack(session, stream);
            if (faces == null || faces.detectedNum == 0 || faces.tokens == null
                    || faces.tokens.length == 0 || faces.tokens[0] == null) {
                return null;
            }
            return InspireFace.ExtractFaceFeature(session, stream, faces.tokens[0]);
        } finally {
            InspireFace.ReleaseImageStream(stream);
        }
    }

    private static List<File> images(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        for (File f : files) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                result.add(f);
            }
        }
        Collections.sort(result, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return result;
    }

    private Bitmap loadSampleFace() throws Exception {
        try (InputStream input = context.getAssets().open("inspireface/kun.jpg")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            assertNotNull("bundled SDK sample face", bitmap);
            return bitmap;
        }
    }

    private static Bitmap jpegRoundTrip(Bitmap src, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!src.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
            return null;
        }
        byte[] bytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private static Bitmap brightness(Bitmap src, float delta) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ColorMatrix matrix = new ColorMatrix(new float[]{
                1, 0, 0, 0, delta,
                0, 1, 0, 0, delta,
                0, 0, 1, 0, delta,
                0, 0, 0, 1, 0});
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }

    private static Bitmap scaled(Bitmap src, float scale) {
        int width = Math.max(2, Math.round(src.getWidth() * scale));
        int height = Math.max(2, Math.round(src.getHeight() * scale));
        return Bitmap.createScaledBitmap(src, width, height, true);
    }

    private static Bitmap mirrored(Bitmap src) {
        Matrix matrix = new Matrix();
        matrix.postScale(-1f, 1f, src.getWidth() / 2f, src.getHeight() / 2f);
        try {
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(),
                    matrix, true);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(Charset.forName("UTF-8")));
        }
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    private static String join(List<String> lines, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }
}
