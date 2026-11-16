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
import com.example.inspireface_example.face.FaceRepository;
import com.example.inspireface_example.face.PersonIdResolver;
import com.insightface.sdk.inspireface.InspireFace;
import com.insightface.sdk.inspireface.base.FaceFeature;
import com.insightface.sdk.inspireface.base.ImageStream;
import com.insightface.sdk.inspireface.base.MultipleFaceData;
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
 * Device-side proof of the EXACT cast flow the user asked for:
 *
 * <pre>
 *   A appears            -> P001
 *   B appears            -> P002
 *   A + B in one frame   -> P001 / P002, never the same id
 *   A leaves completely  -> in-memory bindings dropped
 *   A appears again      -> STILL P001 (new track id, freshly extracted feature)
 *   B appears again      -> STILL P002
 *   newcomer C appears   -> P003 (next unused number, not a reused / skipped one)
 * </pre>
 *
 * <p>Runs against the REAL native SDK and REAL FeatureHub (isolated DB), using the
 * production {@link PersonIdResolver} policy and the same adapter shape as
 * {@code FaceTrackingAnalyzer.GalleryAdapter}.
 *
 * <p><b>Honesty boundary:</b> only one real face (kun.jpg) is bundled. A and A's
 * re-entry are REAL features — the re-entry is re-extracted from a JPEG-compressed,
 * brightened shot, so its ~0.98 cosine is an actual intra-person score. B and C are
 * two mutually dissimilar SYNTHETIC vectors built from A's feature; they exist only
 * to exercise "different person -> next number" and MUST NOT be read as real
 * inter-person similarity or used to calibrate thresholds (B0 still pending).
 */
@RunWith(AndroidJUnit4.class)
public final class PersonFlowAbacTest {

    private static final String TAG = "RealCueFlow";
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

    /** The full A -> B -> (A+B) -> A leaves -> A returns -> B returns -> C flow. */
    @Test
    public void fullCastFlowKeepsEveryPersonsNumber() {
        FaceRepository repo = new FaceRepository(storage, REPO_MODEL);
        assertTrue("FeatureHub open", repo.open());
        try {
            TestGallery gallery = new TestGallery(repo, base);
            PersonIdResolver<FaceFeature, Bitmap> resolver =
                    new PersonIdResolver<>(gallery);

            FaceFeature realA = extractFeature(base);
            FaceFeature personB = negate(realA);       // cos(A,B) = -1
            FaceFeature personC = halfFlip(realA);    // cos(A,C) ~ 0, cos(B,C) ~ 0
            long t = 0L;

            // 1) A appears for the first time: empty gallery -> 3 misses -> P001.
            long idA = appear(resolver, 101, realA, t);
            String labelA = PersonIdResolver.formatLabel(idA);
            Log.i(TAG, "STEP1 A_FIRST -> " + labelA + " gallery=" + repo.query(null).size());
            t += 100L;

            // 2) B appears for the first time: does not match A -> P002.
            long idB = appear(resolver, 102, personB, t);
            String labelB = PersonIdResolver.formatLabel(idB);
            Log.i(TAG, "STEP2 B_FIRST -> " + labelB + " gallery=" + repo.query(null).size());
            t += 100L;

            // 3) A and B in ONE frame, plus a second A-like face: no id sharing.
            resolver.beginFrame(t);
            PersonIdResolver.Result aSame = resolver.resolve(301, realA, base, t);
            PersonIdResolver.Result bSame = resolver.resolve(302, personB, base, t);
            PersonIdResolver.Result aDuplicate = resolver.resolve(303, realA, base, t);
            Log.i(TAG, "STEP3 SAME_FRAME a=" + aSame + " b=" + bSame
                    + " secondA=" + aDuplicate);
            assertEquals(PersonIdResolver.Decision.MATCHED, aSame.decision);
            assertEquals(idA, aSame.personId);
            assertEquals(PersonIdResolver.Decision.MATCHED, bSame.decision);
            assertEquals(idB, bSame.personId);
            assertEquals("a second A-like box must not steal A's id",
                    PersonIdResolver.Decision.SKIPPED, aDuplicate.decision);
            assertFalse(aDuplicate.hasPersonId());
            t += 100L;

            // 4) Everyone leaves: drop in-memory bindings, KEEP the gallery.
            resolver.clear();
            assertEquals(0, resolver.activeBindings());
            assertEquals("gallery must survive a full leave", 2, repo.query(null).size());
            Log.i(TAG, "STEP4 LEAVE_ALL bindings=0 gallery=" + repo.query(null).size());
            t += 100L;

            // 5) A comes back with a BRAND NEW track id and a freshly extracted
            //    feature from a different-looking frame -> must still be P001.
            FaceFeature reentryA = extractFeature(reentryVariant(base));
            resolver.beginFrame(t);
            PersonIdResolver.Result aBack =
                    resolver.resolve(401, reentryA, base, t);
            Log.i(TAG, "STEP5 A_REENTRY " + aBack + " expected=" + labelA);
            assertEquals("A re-entry must be a MATCH, not a new identity",
                    PersonIdResolver.Decision.MATCHED, aBack.decision);
            assertEquals("A re-entry must reuse P001", idA, aBack.personId);
            assertTrue("re-entry similarity should be a real high score",
                    aBack.confidence >= PersonIdResolver.DEFAULT_MATCH_THRESHOLD);
            assertEquals("re-entry must NOT create a 3rd identity",
                    2, repo.query(null).size());
            assertEquals("re-entry must NOT call enroll", 2, gallery.enrollCalls);
            t += 16L;

            // 6) B comes back too -> still P002.
            resolver.beginFrame(t);
            PersonIdResolver.Result bBack = resolver.resolve(402, personB, base, t);
            Log.i(TAG, "STEP6 B_REENTRY " + bBack + " expected=" + labelB);
            assertEquals(PersonIdResolver.Decision.MATCHED, bBack.decision);
            assertEquals(idB, bBack.personId);
            t += 100L;

            // 7) A newcomer C -> next unused number, P003.
            long idC = appear(resolver, 403, personC, t);
            String labelC = PersonIdResolver.formatLabel(idC);
            Log.i(TAG, "STEP7 C_FIRST -> " + labelC + " gallery=" + repo.query(null).size());

            // Final assertions on the whole cast.
            assertEquals("A must be #1", "P001", labelA);
            assertEquals("B must be #2", "P002", labelB);
            assertEquals("newcomer C must be the NEXT unused number", "P003", labelC);
            assertFalse("three people must have three distinct ids",
                    idA == idB || idB == idC || idA == idC);
            assertEquals("exactly three identities, no duplicates",
                    3, repo.query(null).size());
            assertEquals("exactly three enrollments (A, B, C)",
                    3, gallery.enrollCalls);

            Log.i(TAG, "FINAL cast=A:" + labelA + " B:" + labelB + " C:" + labelC
                    + " gallery=" + repo.query(null).size()
                    + " enrollCalls=" + gallery.enrollCalls
                    + " RESULT=PASS");
        } finally {
            repo.close();
        }
    }

    /** A's re-entry must not consume a number: the next newcomer still gets P002. */
    @Test
    public void reentryDoesNotConsumeANewNumber() {
        FaceRepository repo = new FaceRepository(storage, REPO_MODEL);
        assertTrue("FeatureHub open", repo.open());
        try {
            TestGallery gallery = new TestGallery(repo, base);
            PersonIdResolver<FaceFeature, Bitmap> resolver =
                    new PersonIdResolver<>(gallery);
            FaceFeature realA = extractFeature(base);
            long t = 0L;

            long idA = appear(resolver, 11, realA, t);
            assertEquals("P001", PersonIdResolver.formatLabel(idA));
            t += 100L;

            // Full leave, then A returns under a fresh track id.
            resolver.clear();
            t += 100L;
            FaceFeature reentryA = extractFeature(reentryVariant(base));
            resolver.beginFrame(t);
            PersonIdResolver.Result back = resolver.resolve(22, reentryA, base, t);
            Log.i(TAG, "NO_SKIP A_REENTRY " + back);
            assertEquals(PersonIdResolver.Decision.MATCHED, back.decision);
            assertEquals(idA, back.personId);
            assertEquals("still only one identity", 1, repo.query(null).size());
            assertEquals("still only one enrollment", 1, gallery.enrollCalls);
            t += 100L;

            // Now the SECOND person arrives: must be P002, not P003.
            long idB = appear(resolver, 33, negate(realA), t);
            Log.i(TAG, "NO_SKIP B_FIRST -> " + PersonIdResolver.formatLabel(idB));
            assertEquals("a re-entry must not burn a number",
                    "P002", PersonIdResolver.formatLabel(idB));
            assertEquals(2, repo.query(null).size());
        } finally {
            repo.close();
        }
    }

    /**
     * Simulates one person appearing and staying until the production policy
     * (3 consecutive misses) auto-enrolls them. Returns the assigned person id.
     */
    private static long appear(PersonIdResolver<FaceFeature, Bitmap> resolver,
                               int trackId, FaceFeature feature, long startMs) {
        long t = startMs;
        PersonIdResolver.Result result = null;
        for (int i = 0; i < PersonIdResolver.DEFAULT_MISSES_TO_ENROLL; i++) {
            resolver.beginFrame(t);
            result = resolver.resolve(trackId, feature, null, t);
            t += 16L;
        }
        assertNotNull(result);
        assertEquals("appearance should end in auto-enrollment",
                PersonIdResolver.Decision.ENROLLED, result.decision);
        assertTrue(result.hasPersonId());
        return result.personId;
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

    /** Real native feature extraction from a bitmap (used for A and A's re-entry). */
    private static FaceFeature extractFeature(Bitmap bitmap) {
        Session session = FaceEngine.createRecognitionSession();
        assertNotNull(session);
        try {
            ImageStream stream = InspireFace.CreateImageStreamFromBitmap(
                    bitmap, InspireFace.CAMERA_ROTATION_0);
            assertNotNull(stream);
            try {
                MultipleFaceData faces = InspireFace.ExecuteFaceTrack(session, stream);
                assertNotNull(faces);
                assertTrue("face must be detected", faces.detectedNum > 0);
                FaceFeature feature = InspireFace.ExtractFaceFeature(
                        session, stream, faces.tokens[0]);
                assertNotNull(feature);
                assertEquals(InspireFace.GetFeatureLength(), feature.data.length);
                return feature;
            } finally {
                InspireFace.ReleaseImageStream(stream);
            }
        } finally {
            FaceEngine.releaseSession(session);
        }
    }

    /** JPEG q70 + brightness +18: a genuinely different-looking frame of A. */
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

    /** cos(A, B) == -1: a clearly different identity. */
    private static FaceFeature negate(FaceFeature real) {
        FaceFeature other = new FaceFeature();
        other.data = new float[real.data.length];
        for (int i = 0; i < real.data.length; i++) {
            other.data[i] = -real.data[i];
        }
        return other;
    }

    /**
     * cos(A, C) ~ 0 and cos(B, C) ~ 0: a THIRD identity that is neither A nor B.
     * Negating only the first half makes it orthogonal to both A and -A.
     */
    private static FaceFeature halfFlip(FaceFeature real) {
        FaceFeature other = new FaceFeature();
        other.data = new float[real.data.length];
        int half = real.data.length / 2;
        for (int i = 0; i < real.data.length; i++) {
            other.data[i] = i < half ? -real.data[i] : real.data[i];
        }
        return other;
    }

    /** Same three-zone adapter as production FaceTrackingAnalyzer.GalleryAdapter. */
    private static final class TestGallery
            implements PersonIdResolver.Gallery<FaceFeature, Bitmap> {
        final FaceRepository repository;
        /** insert() rejects a null crop; the bundled sample is a placeholder image. */
        final Bitmap crop;
        int enrollCalls;

        TestGallery(FaceRepository repository, Bitmap crop) {
            this.repository = repository;
            this.crop = crop;
        }

        @Override
        public PersonIdResolver.Candidate bestMatch(FaceFeature feature) {
            FaceRepository.SearchResult result = repository.search(feature);
            if (result == null || result.record == null
                    || Float.isNaN(result.confidence)) {
                return new PersonIdResolver.Candidate(-1L, Float.NEGATIVE_INFINITY);
            }
            return new PersonIdResolver.Candidate(
                    result.record.id, result.confidence);
        }

        @Override
        public long enroll(FaceFeature feature, Bitmap ignoredCrop) {
            enrollCalls++;
            FaceRepository.InsertResult inserted =
                    repository.insert("Auto", feature, crop);
            return inserted.success && inserted.record != null
                    ? inserted.record.id : -1L;
        }
    }

    /** Isolated filesDir + SharedPreferences so FeatureHub cannot leak across tests. */
    private static final class IsolatedStorage extends ContextWrapper {
        private final String prefix = "flow-" + UUID.randomUUID() + "-";
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
