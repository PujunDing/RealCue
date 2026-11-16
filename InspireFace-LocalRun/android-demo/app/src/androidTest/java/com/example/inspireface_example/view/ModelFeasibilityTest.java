package com.example.inspireface_example.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.insightface.sdk.inspireface.InspireFace;
import com.insightface.sdk.inspireface.base.FaceFeature;
import com.insightface.sdk.inspireface.base.ImageStream;
import com.insightface.sdk.inspireface.base.MultipleFaceData;
import com.insightface.sdk.inspireface.base.Point2f;
import com.insightface.sdk.inspireface.base.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;

/**
 * Project-added verification (not upstream Demo code).
 *
 * Answers one question: do the bundled InspireFace models actually run on this
 * device? It loads both model packs, detects the bundled sample face, computes
 * dense landmarks, extracts and compares a feature, tracks it across three
 * frames, and checks that a blank frame reports zero faces. Every measured
 * value is written to logcat under tag RealCueModel.
 */
@RunWith(AndroidJUnit4.class)
public final class ModelFeasibilityTest {
    private static final String TAG = "RealCueModel";
    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before public void prepare() throws Exception { SdkTestRuntime.resetWhenIdle(); }
    @After public void release() throws Exception { SdkTestRuntime.resetWhenIdle(); }

    @Test
    public void bothModelPacksDetectLandmarkExtractAndTrack() throws Exception {
        Bitmap bitmap = loadSampleFace();
        try {
            for (String model : new String[]{"Pikachu", "Megatron"}) {
                assertTrue("GlobalLaunch " + model,
                        InspireFace.GlobalLaunch(context, model));
                try {
                    Session detection = FaceEngine.createDetectionSession(320, 3, 0);
                    assertNotNull("detection session " + model, detection);
                    ImageStream stream = InspireFace.CreateImageStreamFromBitmap(
                            bitmap, InspireFace.CAMERA_ROTATION_0);
                    assertNotNull("image stream " + model, stream);
                    try {
                        MultipleFaceData faces =
                                InspireFace.ExecuteFaceTrack(detection, stream);
                        assertNotNull(faces);
                        Log.i(TAG, model + " detectedNum=" + faces.detectedNum);
                        assertTrue("detect sample face with " + model,
                                faces.detectedNum >= 1);

                        Point2f[] landmarks =
                                InspireFace.GetFaceDenseLandmarkFromFaceToken(faces.tokens[0]);
                        assertNotNull(landmarks);
                        Log.i(TAG, model + " denseLandmarks=" + landmarks.length);
                        assertEquals("106 landmarks " + model, 106, landmarks.length);

                        Session recognition = FaceEngine.createRecognitionSession();
                        assertNotNull("recognition session " + model, recognition);
                        try {
                            FaceFeature feature = InspireFace.ExtractFaceFeature(
                                    recognition, stream, faces.tokens[0]);
                            assertNotNull("feature " + model, feature);
                            float similarity = InspireFace.FaceComparison(feature, feature);
                            Log.i(TAG, model + " featureLength=" + feature.data.length
                                    + " selfSimilarity=" + similarity);
                            assertEquals("feature length " + model,
                                    InspireFace.GetFeatureLength(), feature.data.length);
                            assertTrue("self comparison " + model, similarity > 0.99f);
                        } finally {
                            FaceEngine.releaseSession(recognition);
                        }

                        Session tracker = FaceEngine.createFaceTrackingSession(
                                InspireFace.DETECT_MODE_LIGHT_TRACK, 320, 3, 0);
                        assertNotNull("tracker " + model, tracker);
                        try {
                            int firstTrackId = -1;
                            int previousCount = -1;
                            for (int frame = 0; frame < 3; frame++) {
                                MultipleFaceData tracked =
                                        InspireFace.ExecuteFaceTrack(tracker, stream);
                                assertNotNull(tracked);
                                assertTrue("frame " + frame + " detects face " + model,
                                        tracked.detectedNum >= 1);
                                int id = tracked.trackIds[0];
                                int count = tracked.trackCounts[0];
                                Log.i(TAG, model + " frame=" + frame
                                        + " trackId=" + id + " trackCount=" + count);
                                if (firstTrackId < 0) firstTrackId = id;
                                assertEquals("stable track id " + model, firstTrackId, id);
                                assertTrue("track count must not regress " + model,
                                        count >= previousCount);
                                previousCount = count;
                            }
                        } finally {
                            FaceEngine.releaseSession(tracker);
                        }

                        Bitmap blank = Bitmap.createBitmap(bitmap.getWidth(),
                                bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                        try {
                            ImageStream empty = InspireFace.CreateImageStreamFromBitmap(
                                    blank, InspireFace.CAMERA_ROTATION_0);
                            assertNotNull(empty);
                            try {
                                MultipleFaceData none =
                                        InspireFace.ExecuteFaceTrack(detection, empty);
                                Log.i(TAG, model + " blankDetectedNum=" + none.detectedNum);
                                assertEquals("blank frame must have no face " + model,
                                        0, none.detectedNum);
                            } finally {
                                InspireFace.ReleaseImageStream(empty);
                            }
                        } finally {
                            blank.recycle();
                        }
                    } finally {
                        InspireFace.ReleaseImageStream(stream);
                        FaceEngine.releaseSession(detection);
                    }
                } finally {
                    assertTrue("GlobalTerminate " + model, InspireFace.GlobalTerminate());
                }
            }
        } finally {
            bitmap.recycle();
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
}
