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

import java.io.File;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Device-side verification of the stage-B automatic numbering pipeline against the
 * REAL native FeatureHub (not a mock): auto-enroll, leave-and-return id reuse,
 * two identities without cross-talk, and persistence across a repository reopen.
 *
 * <p>Scope caveat: only one real face sample (kun.jpg) is bundled. The "second
 * person" is a synthetic opposite feature vector used to exercise the branch logic,
 * NOT a real inter-person score. These results must NOT be used to calibrate
 * thresholds — that still requires two real subjects (B0).
 */
@RunWith(AndroidJUnit4.class)
public final class AutoIdReentryTest {

    private static final String TAG = "RealCueAutoIdReentry";
    private static final String MODEL = "Pikachu";
    private static final FaceModelPrefs.Model REPO_MODEL = FaceModelPrefs.Model.PIKACHU;

    private IsolatedStorage storage;
    private Bitmap bitmap;
    private FaceFeature realFeature;

    @Before
    public void setUp() throws Exception {
        SdkTestRuntime.resetWhenIdle();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        storage = new IsolatedStorage(context);
        bitmap = loadSample(context);
        assertTrue("GlobalLaunch(" + MODEL + ")",
                InspireFace.GlobalLaunch(context, MODEL));
        realFeature = extractFeature(bitmap);
        assertTrue("feature must be non-empty", realFeature.data.length > 0);
        Log.i(TAG, "SETUP featureLen=" + realFeature.data.length
                + " recommendedThreshold=" + InspireFace.GetRecommendedCosineThreshold());
    }

    @After
    public void tearDown() throws Exception {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        if (storage != null) {
            storage.clear();
        }
        // resetWhenIdle terminates GlobalLaunch if still active.
        SdkTestRuntime.resetWhenIdle();
    }

    @Test
    public void autoEnrollThenSessionResetThenReentryReusesSameId() {
        FaceRepository repo = new FaceRepository(storage, REPO_MODEL);
        assertTrue("FeatureHub open", repo.open());
        try {
            TestGallery gallery = new TestGallery(repo);
            PersonIdResolver<FaceFeature, Bitmap> resolver =
                    new PersonIdResolver<>(gallery);
            long t = 0L;

            PersonIdResolver.Result first = null;
            for (int i = 0; i < 3; i++) {
                resolver.beginFrame(t);
                first = resolver.resolve(101, realFeature, bitmap, t);
                Log.i(TAG, "ENROLL_STEP i=" + i + " -> " + first);
                t += 16L;
            }
            assertEquals(PersonIdResolver.Decision.ENROLLED, first.decision);
            long firstId = first.personId;
            assertTrue(firstId >= 0L);
            assertEquals("P001", PersonIdResolver.formatLabel(firstId));
            assertEquals(1, gallery.enrollCalls);
            assertEquals(1, repo.query(null).size());

            // Person fully leaves the frame: drop all in-memory bindings but keep
            // the persistent gallery. This is exactly the "reset session" button.
            resolver.clear();
            assertEquals(0, resolver.activeBindings());
            assertEquals(1, repo.query(null).size());

            resolver.beginFrame(t);
            PersonIdResolver.Result reentry =
                    resolver.resolve(202, realFeature, bitmap, t); // brand new track id
            Log.i(TAG, "REENTRY_RESULT " + reentry + " expectedId=" + firstId);
            assertEquals("re-entry must reuse the same gallery id",
                    PersonIdResolver.Decision.MATCHED, reentry.decision);
            assertEquals(firstId, reentry.personId);
            assertEquals("re-entry must not create a second identity",
                    1, gallery.enrollCalls);
            assertEquals(1, repo.query(null).size());
        } finally {
            repo.close();
        }
    }

    @Test
    public void twoDistinctIdentitiesGetDistinctIdsAndDoNotCrossTalk() {
        FaceRepository repo = new FaceRepository(storage, REPO_MODEL);
        assertTrue("FeatureHub open", repo.open());
        try {
            // Synthetic opposite vector: cosine(real, other) == -1, i.e. a clearly
            // different identity. Used ONLY to exercise the branch logic.
            FaceFeature other = oppositeFeature(realFeature);
            TestGallery gallery = new TestGallery(repo);
            PersonIdResolver<FaceFeature, Bitmap> resolver =
                    new PersonIdResolver<>(gallery);
            long t = 0L;

            long idA = enroll(resolver, 1, realFeature, t);
            t += 100L;
            long idB = enroll(resolver, 2, other, t);
            Log.i(TAG, "TWO_IDS idA=" + idA + " idB=" + idB
                    + " enrollCalls=" + gallery.enrollCalls);
            assertFalse("two distinct identities must not share an id", idA == idB);
            assertEquals(2, gallery.enrollCalls);
            assertEquals(2, repo.query(null).size());

            // Same frame, two fresh tracks both looking like person A: the second
            // must NOT be allowed to claim A's id (anti-cross-talk).
            t += 100L;
            resolver.beginFrame(t);
            PersonIdResolver.Result a = resolver.resolve(301, realFeature, bitmap, t);
            PersonIdResolver.Result b = resolver.resolve(302, realFeature, bitmap, t);
            Log.i(TAG, "SAME_FRAME a=" + a + " b=" + b);
            assertEquals(PersonIdResolver.Decision.MATCHED, a.decision);
            assertEquals(idA, a.personId);
            assertEquals(PersonIdResolver.Decision.SKIPPED, b.decision);
            assertFalse(b.hasPersonId());
            assertEquals(2, repo.query(null).size());

            // Person B is still recognised as B on the next frame.
            t += 16L;
            resolver.beginFrame(t);
            PersonIdResolver.Result bAgain = resolver.resolve(303, other, bitmap, t);
            Log.i(TAG, "B_AGAIN " + bAgain);
            assertEquals(PersonIdResolver.Decision.MATCHED, bAgain.decision);
            assertEquals(idB, bAgain.personId);
        } finally {
            repo.close();
        }
    }

    @Test
    public void gallerySurvivesRepositoryReopenWithAFreshResolver() {
        FaceRepository repo = new FaceRepository(storage, REPO_MODEL);
        assertTrue("FeatureHub open", repo.open());
        long firstId;
        try {
            TestGallery gallery = new TestGallery(repo);
            PersonIdResolver<FaceFeature, Bitmap> resolver =
                    new PersonIdResolver<>(gallery);
            firstId = enroll(resolver, 11, realFeature, 0L);
            assertEquals(1, repo.query(null).size());
        } finally {
            repo.close();
        }

        // Simulate an app restart: new repository instance + brand new resolver
        // with empty in-memory state. Only persistent FeatureHub data can help.
        FaceRepository reopened = new FaceRepository(storage, REPO_MODEL);
        assertTrue("FeatureHub re-open", reopened.open());
        try {
            assertEquals(1, reopened.query(null).size());
            TestGallery gallery2 = new TestGallery(reopened);
            PersonIdResolver<FaceFeature, Bitmap> resolver2 =
                    new PersonIdResolver<>(gallery2);
            resolver2.beginFrame(1_000L);
            PersonIdResolver.Result afterRestart =
                    resolver2.resolve(99, realFeature, bitmap, 1_000L);
            Log.i(TAG, "AFTER_RESTART " + afterRestart + " expectedId=" + firstId);
            assertEquals(PersonIdResolver.Decision.MATCHED, afterRestart.decision);
            assertEquals(firstId, afterRestart.personId);
            assertEquals(0, gallery2.enrollCalls);
        } finally {
            reopened.close();
        }
    }

    /** Uses the bundled sample bitmap as the crop; insert() rejects a null crop. */
    private long enroll(PersonIdResolver<FaceFeature, Bitmap> resolver,
                        int trackId, FaceFeature feature, long startMs) {
        long t = startMs;
        PersonIdResolver.Result result = null;
        for (int i = 0; i < 3; i++) {
            resolver.beginFrame(t);
            result = resolver.resolve(trackId, feature, bitmap, t);
            t += 16L;
        }
        assertNotNull(result);
        assertEquals("enrollment should happen on the 3rd consecutive miss",
                PersonIdResolver.Decision.ENROLLED, result.decision);
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
                assertTrue("sample face must be detected", faces.detectedNum > 0);
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

    private static FaceFeature oppositeFeature(FaceFeature real) {
        float norm = 0f;
        for (float v : real.data) {
            norm += v * v;
        }
        assertTrue("real feature must be non-zero", norm > 0f);
        FaceFeature other = new FaceFeature();
        other.data = new float[real.data.length];
        for (int i = 0; i < real.data.length; i++) {
            other.data[i] = -real.data[i];
        }
        return other;
    }

    /** Same three-zone adapter as production, but with a Bitmap as the crop context. */
    private static final class TestGallery
            implements PersonIdResolver.Gallery<FaceFeature, Bitmap> {
        final FaceRepository repository;
        int enrollCalls;

        TestGallery(FaceRepository repository) {
            this.repository = repository;
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
                    repository.insert("AutoTest", feature, ignoredCrop);
            return inserted.success && inserted.record != null
                    ? inserted.record.id : -1L;
        }
    }

    private static final class IsolatedStorage extends ContextWrapper {
        private final String prefix = "autoid-" + UUID.randomUUID() + "-";
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
            assertTrue("Remove test storage " + file, !file.exists() || file.delete());
        }
    }
}
