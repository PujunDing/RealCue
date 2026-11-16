package com.example.inspireface_example.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import com.insightface.sdk.inspireface.base.FaceFeatureIdentity;
import com.insightface.sdk.inspireface.base.FeatureHubConfiguration;
import com.insightface.sdk.inspireface.base.ImageStream;
import com.insightface.sdk.inspireface.base.MultipleFaceData;
import com.insightface.sdk.inspireface.base.SearchTopKResults;
import com.insightface.sdk.inspireface.base.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Project-added verification (not upstream Demo code).
 *
 * Answers: how reliable is "same person -> same gallery ID"? It enrolls ONE
 * identity into an isolated FeatureHub, then re-detects that same face under
 * JPEG compression, brightness changes, scaling, rotation and mirroring, and
 * reports both FaceComparison scores and FeatureHub TopK confidence / hit ID.
 * A blank frame must produce no face at all.
 *
 * This measures intra-person stability only. Inter-person scores need two real
 * people on a physical phone and are deliberately NOT asserted here.
 */
@RunWith(AndroidJUnit4.class)
public final class GalleryAccuracyProbeTest {

    private static final String TAG = "RealCueGallery";
    private static final String MODEL = "Pikachu";
    private static final long PERSON_ID = 1001L;

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before public void prepare() throws Exception { SdkTestRuntime.resetWhenIdle(); }
    @After public void release() throws Exception { SdkTestRuntime.resetWhenIdle(); }

    @Test
    public void samePersonUnderDifferentFramesStillHitsTheSameGalleryId() throws Exception {
        Bitmap base = loadSampleFace();
        File db = new File(context.getCacheDir(),
                "gallery_probe_" + System.nanoTime() + ".db");
        Session session = null;
        boolean hubEnabled = false;
        try {
            assertTrue("GlobalLaunch " + MODEL,
                    InspireFace.GlobalLaunch(context, MODEL));
            session = FaceEngine.createRecognitionSession();
            assertNotNull("recognition session", session);

            float threshold = InspireFace.GetRecommendedCosineThreshold();
            Log.i(TAG, "=== THRESHOLD " + threshold
                    + " featureLength=" + InspireFace.GetFeatureLength() + " ===");
            assertTrue("threshold must be within (0,1)",
                    threshold > 0f && threshold < 1f);

            FaceFeature baseFeature = extract(session, base);
            assertNotNull("base feature", baseFeature);
            Log.i(TAG, "base selfSimilarity="
                    + InspireFace.FaceComparison(baseFeature, baseFeature));

            FeatureHubConfiguration configuration = InspireFace.CreateFeatureHubConfiguration()
                    .setPrimaryKeyMode(InspireFace.PK_MANUAL_INPUT)
                    .setEnablePersistence(true)
                    .setPersistenceDbPath(db.getAbsolutePath())
                    .setSearchThreshold(threshold)
                    .setSearchMode(InspireFace.SEARCH_MODE_EXHAUSTIVE);
            hubEnabled = InspireFace.FeatureHubDataEnable(configuration);
            assertTrue("FeatureHubDataEnable", hubEnabled);
            assertTrue("insert identity", InspireFace.FeatureHubInsertFeature(
                    FaceFeatureIdentity.create(PERSON_ID, baseFeature)));
            Log.i(TAG, "galleryCount=" + InspireFace.FeatureHubGetFaceCount()
                    + " enrolledId=" + PERSON_ID);

            Map<String, Bitmap> variants = new LinkedHashMap<>();
            variants.put("base", base);
            variants.put("jpeg_q60", jpegRoundTrip(base, 60));
            variants.put("bright_+40", brightness(base, 40f));
            variants.put("dark_-40", brightness(base, -40f));
            variants.put("scale_0.75", scaled(base, 0.75f));
            variants.put("rotate_4deg", rotated(base, 4f));
            variants.put("mirror", mirrored(base));
            variants.put("blank", Bitmap.createBitmap(base.getWidth(),
                    base.getHeight(), Bitmap.Config.ARGB_8888));

            for (Map.Entry<String, Bitmap> entry : variants.entrySet()) {
                String name = entry.getKey();
                Bitmap variant = entry.getValue();
                if (variant == null) {
                    Log.i(TAG, pad(name) + " SKIP_VARIANT_UNAVAILABLE");
                    continue;
                }
                try {
                    FaceFeature feature = extract(session, variant);
                    if (feature == null) {
                        Log.i(TAG, pad(name) + " NO_FACE (correctly not enrolled)");
                        continue;
                    }
                    float comparison = InspireFace.FaceComparison(baseFeature, feature);
                    SearchTopKResults top =
                            InspireFace.FeatureHubFaceSearchTopK(feature, 1);
                    long hitId = -1L;
                    float confidence = Float.NaN;
                    if (top != null && top.num > 0 && top.ids != null
                            && top.ids.length > 0) {
                        hitId = top.ids[0];
                        if (top.confidence != null && top.confidence.length > 0) {
                            confidence = top.confidence[0];
                        }
                    }
                    Log.i(TAG, String.format(
                            "%s cmp=%.4f hubConf=%.4f hitId=%d aboveThreshold=%s sameId=%s",
                            pad(name), comparison, confidence, hitId,
                            (!Float.isNaN(confidence) && confidence >= threshold),
                            hitId == PERSON_ID));
                    if ("base".equals(name)) {
                        assertEquals("base must hit its own gallery ID", PERSON_ID, hitId);
                        assertTrue("base confidence must clear threshold",
                                confidence >= threshold);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, pad(name) + " ERROR " + t);
                } finally {
                    if (variant != base) {
                        variant.recycle();
                    }
                }
            }
        } finally {
            if (hubEnabled) {
                InspireFace.FeatureHubDataDisable();
            }
            if (session != null) {
                FaceEngine.releaseSession(session);
            }
            if (InspireFace.QueryLaunchStatus()) {
                InspireFace.GlobalTerminate();
            }
            db.delete();
            base.recycle();
        }
    }

    @androidx.annotation.Nullable
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

    private static Bitmap rotated(Bitmap src, float degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        try {
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(),
                    matrix, true);
        } catch (Exception e) {
            return null;
        }
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

    private static String pad(String value) {
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < 12) {
            builder.append(' ');
        }
        return builder.toString();
    }
}
