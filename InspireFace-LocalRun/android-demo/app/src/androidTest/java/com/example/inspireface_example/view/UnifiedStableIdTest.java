package com.example.inspireface_example.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.inspireface_example.FaceModelPrefs;
import com.example.inspireface_example.face.FaceImageProcessor;
import com.example.inspireface_example.face.FaceRepository;
import com.example.inspireface_example.face.PersonIdResolver;
import com.insightface.sdk.inspireface.InspireFace;
import com.insightface.sdk.inspireface.base.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Device-side verification for the "make EVERY numbering surface persistent"
 * refactor: the still-image detection page now uses
 * {@link FaceImageProcessor#detectWithLandmarksAndFeatures(Session, Bitmap)}
 * plus the same FeatureHub gallery as the video tracker, so an image box shows
 * {@code P001} instead of the 1/2/3 array index.
 *
 * <p>Covered here against the REAL native SDK (no mocks):
 * <ul>
 *   <li>the new still-image pass really returns a 512-d feature, an aligned crop
 *       and 106 dense landmarks in a single detection;</li>
 *   <li>a recognition Session is required — the detection-only Session yields no
 *       feature, which is exactly why FaceDetectionActivity swaps sessions;</li>
 *   <li>"leave and return" (a re-framed/JPEG-recompressed/brightened shot of the
 *       same face) resolves to the SAME persistent P001 label, with no duplicate
 *       identity inserted;</li>
 *   <li>the gallery label is searchable as P001 / p001 / 1, so a user who sees
 *       P001 on a box can find that identity in Face management.</li>
 * </ul>
 *
 * <p>Scope caveat: only one real face (kun.jpg) is bundled, so this proves
 * intra-person stability. Inter-person separation still needs two real subjects
 * on a physical phone (B0). Do NOT use these scores to calibrate thresholds.
 */
@RunWith(AndroidJUnit4.class)
public final class UnifiedStableIdTest {

    private static final String TAG = "RealCueUnified";
    private static final String MODEL = "Pikachu";
    private static final FaceModelPrefs.Model REPO_MODEL = FaceModelPrefs.Model.PIKACHU;

    private IsolatedStorage storage;
    private Bitmap base;
    private Context appContext;

    @Before
    public void setUp() throws Exception {
        SdkTestRuntime.resetWhenIdle();
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        storage = new IsolatedStorage(appContext);
        base = loadSample(appContext);
        assertTrue("GlobalLaunch(" + MODEL + ")",
                InspireFace.GlobalLaunch(appContext, MODEL));
        Log.i(TAG, "SETUP featureLength=" + InspireFace.GetFeatureLength()
                + " recommendedThreshold=" + InspireFace.GetRecommendedCosineThreshold());
    }

    @After
    public void tearDown() throws Exception {
        if (base != null && !base.isRecycled()) {
            base.recycle();
        }
        if (storage != null) {
            storage.clear();
        }
        SdkTestRuntime.resetWhenIdle();
    }

    /** The new still-image pass must materialize everything the stable labels need. */
    @Test
    public void stillImageDetectionProducesFeaturesCropsAnd106Landmarks() {
        Session session = FaceEngine.createRecognitionSession();
        assertNotNull("recognition session", session);
        FaceImageProcessor.Result result = null;
        try {
            result = FaceImageProcessor.detectWithLandmarksAndFeatures(session, base);
            assertNotNull(result);
            assertEquals("sample face must be detected",
                    FaceImageProcessor.Status.READY, result.status);
            assertTrue(result.candidates.length > 0);
            FaceImageProcessor.Candidate first = result.candidates[0];

            assertNotNull("512-d feature", first.feature);
            assertEquals("feature length", InspireFace.GetFeatureLength(),
                    first.feature.data.length);
            assertNotNull("aligned crop for auto-enrollment", first.crop);
            assertFalse("crop must be live", first.crop.isRecycled());
            assertNotNull("dense landmarks", first.denseLandmarks);
            assertEquals("native 106-point dense landmarks",
                    106, first.denseLandmarks.length);

            Log.i(TAG, "STILL_DETECT faces=" + result.candidates.length
                    + " featureLen=" + first.feature.data.length
                    + " crop=" + first.crop.getWidth() + "x" + first.crop.getHeight()
                    + " landmarks=" + first.denseLandmarks.length);
        } finally {
            if (result != null) {
                FaceImageProcessor.recycleCrops(result.candidates, -1);
            }
            FaceEngine.releaseSession(session);
        }
    }

    /** Documents why FaceDetectionActivity must switch to a recognition Session. */
    @Test
    public void detectionOnlySessionHasNoFeatureButRecognitionSessionDoes() {
        Session plain = FaceEngine.createDetectionSession(320, 10, 0);
        assertNotNull("detection session", plain);
        FaceImageProcessor.Result legacy = null;
        try {
            legacy = FaceImageProcessor.detectWithLandmarks(plain, base);
            assertNotNull(legacy);
            assertEquals(FaceImageProcessor.Status.READY, legacy.status);
            assertTrue(legacy.candidates.length > 0);
            assertEquals("legacy path has no feature",
                    null, legacy.candidates[0].feature);
            assertEquals("legacy path has no crop",
                    null, legacy.candidates[0].crop);
            assertNotNull("legacy path still has dense landmarks",
                    legacy.candidates[0].denseLandmarks);
        } finally {
            if (legacy != null) {
                FaceImageProcessor.recycleCrops(legacy.candidates, -1);
            }
            FaceEngine.releaseSession(plain);
        }

        Session recognition = FaceEngine.createRecognitionSession(320, 10, 0);
        assertNotNull("recognition session", recognition);
        FaceImageProcessor.Result stable = null;
        try {
            stable = FaceImageProcessor.detectWithLandmarksAndFeatures(recognition, base);
            assertNotNull(stable);
            assertEquals(FaceImageProcessor.Status.READY, stable.status);
            assertTrue(stable.candidates.length > 0);
            assertNotNull("stable path must have a feature",
                    stable.candidates[0].feature);
            assertNotNull("stable path must have a crop",
                    stable.candidates[0].crop);
            Log.i(TAG, "SESSION_SWITCH legacy_feature=null stable_featureLen="
                    + stable.candidates[0].feature.data.length);
        } finally {
            if (stable != null) {
                FaceImageProcessor.recycleCrops(stable.candidates, -1);
            }
            FaceEngine.releaseSession(recognition);
        }
    }

    /** The core user complaint, on the IMAGE path: same face, new frame, same P001. */
    @Test
    public void leavingAndReturningReusesSameGalleryLabel() {
        FaceRepository repo = new FaceRepository(storage, REPO_MODEL);
        assertTrue("FeatureHub open", repo.open());
        Session session = FaceEngine.createRecognitionSession();
        assertNotNull(session);
        try {
            FaceImageProcessor.Result first = null;
            String firstLabel;
            try {
                first = FaceImageProcessor.detectWithLandmarksAndFeatures(session, base);
                assertEquals(FaceImageProcessor.Status.READY, first.status);
                firstLabel = resolveLikeActivity(repo, first.candidates);
            } finally {
                if (first != null) {
                    FaceImageProcessor.recycleCrops(first.candidates, -1);
                }
            }
            assertEquals("first appearance auto-enrolls P001", "P001", firstLabel);
            assertEquals("exactly one identity", 1, repo.query(null).size());
            Log.i(TAG, "FIRST_APPEARANCE label=" + firstLabel
                    + " gallery=" + repo.query(null).size());

            // Simulate the person leaving the frame and coming back: a re-framed,
            // JPEG-recompressed, slightly brighter shot of the SAME face.
            Bitmap reentry = reentryVariant(base);
            FaceImageProcessor.Result second = null;
            String secondLabel;
            try {
                try {
                    second = FaceImageProcessor.detectWithLandmarksAndFeatures(session, reentry);
                    assertEquals("face must still be detected after re-entry",
                            FaceImageProcessor.Status.READY, second.status);
                    secondLabel = resolveLikeActivity(repo, second.candidates);
                } finally {
                    if (second != null) {
                        FaceImageProcessor.recycleCrops(second.candidates, -1);
                    }
                }
            } finally {
                if (!reentry.isRecycled()) {
                    reentry.recycle();
                }
            }
            Log.i(TAG, "REENTRY first=" + firstLabel + " second=" + secondLabel
                    + " gallery=" + repo.query(null).size());
            assertEquals("re-entry must reuse the same persistent label",
                    firstLabel, secondLabel);
            assertEquals("re-entry must NOT create a second identity",
                    1, repo.query(null).size());
        } finally {
            FaceEngine.releaseSession(session);
            repo.close();
        }
    }

    /** Verifies the gallery can be searched by the P001 label shown on the boxes. */
    @Test
    public void gallerySearchAcceptsPersistentP001Label() {
        FaceRepository repo = new FaceRepository(storage, REPO_MODEL);
        assertTrue("FeatureHub open", repo.open());
        Session session = FaceEngine.createRecognitionSession();
        assertNotNull(session);
        FaceImageProcessor.Result result = null;
        try {
            result = FaceImageProcessor.detectWithLandmarksAndFeatures(session, base);
            assertEquals(FaceImageProcessor.Status.READY, result.status);
            assertEquals("P001", resolveLikeActivity(repo, result.candidates));

            assertEquals("P001 searchable", 1, repo.query("P001").size());
            assertEquals("lowercase p001 searchable", 1, repo.query("p001").size());
            assertEquals("numeric id still searchable", 1, repo.query("1").size());
            assertTrue("unknown label returns nothing", repo.query("P999").isEmpty());
            Log.i(TAG, "QUERY_LABEL P001=" + repo.query("P001").size()
                    + " p001=" + repo.query("p001").size()
                    + " numeric=" + repo.query("1").size()
                    + " P999=" + repo.query("P999").size());
        } finally {
            if (result != null) {
                FaceImageProcessor.recycleCrops(result.candidates, -1);
            }
            FaceEngine.releaseSession(session);
            repo.close();
        }
    }

    /**
     * Same policy as FaceDetectionActivity.resolveStableLabels() for a single
     * face: clear hit -> reuse its label, otherwise auto-enroll. Single frame
     * has no temporal evidence, so there is no "wait N misses" here.
     */
    private String resolveLikeActivity(FaceRepository repo,
                                       FaceImageProcessor.Candidate[] found) {
        assertNotNull(found);
        assertTrue(found.length > 0);
        Set<Long> claimed = new HashSet<>();
        FaceImageProcessor.Candidate candidate = found[0];
        assertNotNull("feature", candidate.feature);

        FaceRepository.SearchResult hit = repo.search(candidate.feature);
        if (hit != null && hit.record != null && !Float.isNaN(hit.confidence)
                && hit.confidence >= PersonIdResolver.DEFAULT_MATCH_THRESHOLD
                && claimed.add(hit.record.id)) {
            Log.i(TAG, "RESOLVE matched id=" + hit.record.id
                    + " confidence=" + hit.confidence
                    + " threshold=" + hit.threshold);
            return PersonIdResolver.formatLabel(hit.record.id);
        }
        assertNotNull("crop for enrollment", candidate.crop);
        FaceRepository.InsertResult inserted =
                repo.insert("Auto", candidate.feature, candidate.crop);
        assertTrue("auto-enrollment must succeed", inserted.success);
        assertNotNull("inserted record", inserted.record);
        Log.i(TAG, "RESOLVE enrolled id=" + inserted.record.id
                + " searchConfidence=" + (hit == null ? "null" : hit.confidence));
        return PersonIdResolver.formatLabel(inserted.record.id);
    }

    private static Bitmap loadSample(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("inspireface/kun.jpg")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeStream(input, null, options);
            assertNotNull("bundled kun.jpg", decoded);
            return decoded;
        }
    }

    /** JPEG round-trip + mild brightness shift, keeping the same identity. */
    private static Bitmap reentryVariant(Bitmap src) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertTrue(src.compress(Bitmap.CompressFormat.JPEG, 70, buffer));
        Bitmap jpeg = BitmapFactory.decodeByteArray(
                buffer.toByteArray(), 0, buffer.size());
        assertNotNull("jpeg round-trip", jpeg);
        Bitmap copy = Bitmap.createBitmap(
                jpeg.getWidth(), jpeg.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(copy);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ColorMatrix matrix = new ColorMatrix(new float[]{
                1, 0, 0, 0, 18,
                0, 1, 0, 0, 18,
                0, 0, 1, 0, 18,
                0, 0, 0, 1, 0});
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(jpeg, 0f, 0f, paint);
        if (!jpeg.isRecycled()) {
            jpeg.recycle();
        }
        return copy;
    }

    /** Isolated filesDir + SharedPreferences so FeatureHub cannot leak across tests. */
    private static final class IsolatedStorage extends ContextWrapper {
        private final String prefix = "unified-" + UUID.randomUUID() + "-";
        private final Set<String> preferences = new HashSet<>();
        private final File directory;

        IsolatedStorage(Context context) {
            super(context);
            directory = new File(context.getCacheDir(), prefix);
            assertTrue(directory.mkdirs());
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public File getFilesDir() {
            return directory;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            String isolatedName = prefix + name;
            preferences.add(isolatedName);
            return getBaseContext().getSharedPreferences(isolatedName, mode);
        }

        void clear() {
            for (String name : preferences) {
                getBaseContext().deleteSharedPreferences(name);
            }
            delete(directory);
        }

        private static void delete(File file) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
            assertTrue("Remove test storage " + file,
                    !file.exists() || file.delete());
        }
    }
}
