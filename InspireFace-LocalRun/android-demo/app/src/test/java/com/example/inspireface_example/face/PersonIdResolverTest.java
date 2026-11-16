package com.example.inspireface_example.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.inspireface_example.face.PersonIdResolver.Candidate;
import com.example.inspireface_example.face.PersonIdResolver.Decision;
import com.example.inspireface_example.face.PersonIdResolver.Gallery;
import com.example.inspireface_example.face.PersonIdResolver.Result;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM-only tests for the ID-assignment policy. No Android framework, no native
 * handles: {@code F} and {@code C} are plain Strings and the gallery is a fake.
 */
public class PersonIdResolverTest {

    private static final float SAME_PERSON = 0.95f;
    private static final float DIFFERENT_PERSON = 0.20f;

    private static final class FakeGallery implements Gallery<String, String> {
        final Map<Long, String> identities = new LinkedHashMap<>();
        long nextId = 1L;
        int matchCalls;
        int enrollCalls;
        boolean failEnroll;
        boolean forceNoCandidate;
        /** When non-null, overrides the automatic same/different scoring. */
        Float forcedConfidence;

        void seed(long id, String feature) {
            identities.put(id, feature);
            if (id >= nextId) {
                nextId = id + 1L;
            }
        }

        @Override
        public Candidate bestMatch(String feature) {
            matchCalls++;
            if (forceNoCandidate || identities.isEmpty()) {
                return null;
            }
            // A forced score must reach the resolver verbatim. Comparing NaN with
            // NEGATIVE_INFINITY is always false, so a max() loop would silently
            // rewrite NaN into "no candidate" and hide the boundary under test.
            if (forcedConfidence != null) {
                long id = identities.keySet().iterator().next();
                return new Candidate(id, forcedConfidence);
            }
            long bestId = -1L;
            float bestConfidence = Float.NEGATIVE_INFINITY;
            for (Map.Entry<Long, String> entry : identities.entrySet()) {
                float score = entry.getValue().equals(feature)
                        ? SAME_PERSON : DIFFERENT_PERSON;
                if (score > bestConfidence) {
                    bestConfidence = score;
                    bestId = entry.getKey();
                }
            }
            return new Candidate(bestId, bestConfidence);
        }

        @Override
        public long enroll(String feature, String context) {
            enrollCalls++;
            if (failEnroll) {
                return -1L;
            }
            long id = nextId++;
            identities.put(id, feature);
            return id;
        }
    }

    private static PersonIdResolver<String, String> resolver(FakeGallery gallery) {
        return new PersonIdResolver<>(gallery);
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void emptyGalleryEnrollsOnlyAfterConsecutiveMisses() {
        FakeGallery g = new FakeGallery();
        PersonIdResolver<String, String> r = resolver(g);

        r.beginFrame(0L);
        assertEquals(Decision.PENDING, r.resolve(11, "A", "crop", 0L).decision);
        r.beginFrame(16L);
        assertEquals(Decision.PENDING, r.resolve(11, "A", "crop", 16L).decision);
        r.beginFrame(32L);
        Result third = r.resolve(11, "A", "crop", 32L);

        assertEquals(Decision.ENROLLED, third.decision);
        assertEquals(1L, third.personId);
        assertEquals("P001", third.label());
        assertEquals(1, g.enrollCalls);
        assertEquals(1, g.identities.size());
        assertEquals(1L, r.enrolledCount());
        assertEquals(1, r.activeBindings());

        // An already-bound track keeps its id and does not search the gallery again.
        int before = g.matchCalls;
        r.beginFrame(48L);
        Result again = r.resolve(11, "A", "crop", 48L);
        assertEquals(Decision.MATCHED, again.decision);
        assertEquals(1L, again.personId);
        assertEquals(before, g.matchCalls);
    }

    @Test
    public void grayZoneNeitherMatchesNorEnrolls() {
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forcedConfidence = 0.42f; // between 0.36 and 0.48
        PersonIdResolver<String, String> r = resolver(g);

        for (int i = 0; i < 12; i++) {
            long now = i * 16L;
            r.beginFrame(now);
            Result result = r.resolve(1, "A", null, now);
            assertEquals("frame " + i, Decision.PENDING, result.decision);
            assertFalse(result.hasPersonId());
        }
        assertEquals(0, g.enrollCalls);
        assertEquals(-1L, r.personFor(1));
        assertEquals(0, r.activeBindings());
    }

    @Test
    public void clearHitBindsAnExistingPersonWithoutEnrolling() {
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forcedConfidence = 0.91f;
        PersonIdResolver<String, String> r = resolver(g);

        r.beginFrame(0L);
        Result result = r.resolve(3, "A", null, 0L);

        assertEquals(Decision.MATCHED, result.decision);
        assertEquals(7L, result.personId);
        assertEquals(0.91f, result.confidence, 0.0001f);
        assertEquals(0, g.enrollCalls);
        assertEquals(7L, r.personFor(3));
    }

    @Test
    public void sameFrameExclusivePreventsTwoFacesSharingOnePersonId() {
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forcedConfidence = 0.91f;
        PersonIdResolver<String, String> r = resolver(g);

        r.beginFrame(0L);
        assertEquals(7L, r.resolve(1, "A", null, 0L).personId);
        Result second = r.resolve(2, "A", null, 0L);
        assertEquals(Decision.SKIPPED, second.decision);
        assertFalse(second.hasPersonId());
        assertEquals(-1L, r.personFor(2));
        assertEquals(1, r.activeBindings());

        // On a later frame the same identity can legitimately be matched again
        // (that is a re-entry, not cross-talk).
        r.beginFrame(16L);
        assertEquals(7L, r.resolve(2, "A", null, 16L).personId);
    }

    @Test
    public void expiredTrackRebindsToSamePersonAfterFullReentry() {
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forcedConfidence = 0.93f;
        PersonIdResolver<String, String> r = resolver(g);
        r.setTrackTtlMs(100L);

        r.beginFrame(0L);
        assertEquals(7L, r.resolve(1, "A", null, 0L).personId);

        // Person fully leaves: the track binding expires, but the gallery record
        // must survive. That is what makes "leave and return keeps P007" work.
        r.beginFrame(500L);
        assertEquals(0, r.activeBindings());
        assertEquals(1, g.identities.size());
        assertFalse(g.identities.isEmpty());

        Result reentry = r.resolve(2, "A", null, 500L); // brand new track id
        assertEquals(Decision.MATCHED, reentry.decision);
        assertEquals(7L, reentry.personId);
        assertEquals(0, g.enrollCalls);
        assertEquals(0L, r.enrolledCount());
    }

    @Test
    public void enrollFailureIsNotBoundAndCanBeRetried() {
        FakeGallery g = new FakeGallery();
        g.failEnroll = true;
        PersonIdResolver<String, String> r = resolver(g);
        r.setMissesToEnroll(2);

        r.beginFrame(0L);
        assertEquals(Decision.PENDING, r.resolve(1, "A", null, 0L).decision);
        r.beginFrame(16L);
        Result failed = r.resolve(1, "A", null, 16L);
        assertEquals(Decision.ENROLL_FAILED, failed.decision);
        assertFalse(failed.hasPersonId());
        assertEquals(-1L, r.personFor(1));
        assertEquals(0, r.activeBindings());
        assertEquals(0, g.identities.size());

        g.failEnroll = false;
        r.beginFrame(32L);
        assertEquals(Decision.PENDING, r.resolve(1, "A", null, 32L).decision);
        r.beginFrame(48L);
        Result recovered = r.resolve(1, "A", null, 48L);
        assertEquals(Decision.ENROLLED, recovered.decision);
        assertEquals(1L, recovered.personId);
    }

    @Test
    public void clearAndForgetTrackOnlyDropMemoryBindings() {
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forcedConfidence = 0.93f;
        PersonIdResolver<String, String> r = resolver(g);

        r.beginFrame(0L);
        assertEquals(7L, r.resolve(1, "A", null, 0L).personId);
        r.clear();
        assertEquals(0, r.activeBindings());
        assertEquals(1, g.identities.size());

        r.beginFrame(16L);
        Result again = r.resolve(9, "A", null, 16L);
        assertEquals(Decision.MATCHED, again.decision);
        assertEquals(7L, again.personId);

        r.forgetTrack(9);
        assertEquals(-1L, r.personFor(9));
        assertEquals(1, g.identities.size());
    }

    @Test
    public void nanConfidenceNeverProducesAMatchOrANewIdentity() {
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forcedConfidence = Float.NaN;
        PersonIdResolver<String, String> r = resolver(g);

        for (int i = 0; i < 8; i++) {
            long now = i * 16L;
            r.beginFrame(now);
            Result result = r.resolve(1, "A", null, now);
            assertEquals(Decision.PENDING, result.decision);
        }
        assertEquals(0, g.enrollCalls);
        assertEquals(-1L, r.personFor(1));
    }

    @Test
    public void nullFeatureIsSkippedWithoutTouchingTheGallery() {
        FakeGallery g = new FakeGallery();
        PersonIdResolver<String, String> r = resolver(g);
        r.beginFrame(0L);
        Result result = r.resolve(1, null, null, 0L);
        assertEquals(Decision.SKIPPED, result.decision);
        assertEquals(0, g.matchCalls);
        assertEquals(0, g.enrollCalls);
    }

    @Test
    public void noCandidateIsTreatedAsDefiniteStranger() {
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forceNoCandidate = true;
        PersonIdResolver<String, String> r = resolver(g);
        r.setMissesToEnroll(2);

        r.beginFrame(0L);
        assertEquals(Decision.PENDING, r.resolve(1, "B", "crop", 0L).decision);
        r.beginFrame(16L);
        Result result = r.resolve(1, "B", "crop", 16L);
        assertEquals(Decision.ENROLLED, result.decision);
        assertEquals(8L, result.personId); // seeded id 7 still occupied
        assertEquals(1L, r.enrolledCount());
    }

    @Test
    public void twoDifferentPeopleGetDistinctAutoIds() {
        FakeGallery g = new FakeGallery();
        PersonIdResolver<String, String> r = resolver(g);
        r.setMissesToEnroll(2);

        long now = 0L;
        for (String person : new String[]{"A", "B"}) {
            r.beginFrame(now);
            assertEquals(Decision.PENDING, r.resolve(person.hashCode(), person, "crop", now).decision);
            now += 16L;
            r.beginFrame(now);
            Result result = r.resolve(person.hashCode(), person, "crop", now);
            assertEquals(Decision.ENROLLED, result.decision);
            now += 16L;
        }
        assertEquals(2, g.identities.size());
        assertEquals(1L, r.personFor("A".hashCode()));
        assertEquals(2L, r.personFor("B".hashCode()));
        assertEquals(2L, r.enrolledCount());

        // A third frame for A must keep P001 instead of creating P003.
        r.beginFrame(now);
        assertEquals(1L, r.resolve("A".hashCode(), "A", "crop", now).personId);
        assertEquals(2, g.identities.size());
    }

    @Test
    public void thresholdConfigurationValidatesAndApplies() {
        PersonIdResolver<String, String> r = resolver(new FakeGallery());
        assertInvalid(() -> r.configureThresholds(0.30f, 0.50f));
        assertInvalid(() -> r.configureThresholds(0.50f, -0.10f));
        assertInvalid(() -> r.configureThresholds(1.20f, 0.30f));
        assertInvalid(() -> r.configureThresholds(Float.NaN, 0.30f));
        assertInvalid(() -> r.setMissesToEnroll(0));
        assertInvalid(() -> r.setTrackTtlMs(-1L));

        r.configureThresholds(0.62f, 0.40f);
        assertEquals(0.62f, r.matchThreshold(), 0.0001f);
        assertEquals(0.40f, r.newThreshold(), 0.0001f);

        // With the raised match threshold, 0.55 is now gray, not a definite hit.
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forcedConfidence = 0.55f;
        PersonIdResolver<String, String> strict = resolver(g);
        strict.configureThresholds(0.62f, 0.40f);
        strict.beginFrame(0L);
        assertEquals(Decision.PENDING, strict.resolve(1, "A", null, 0L).decision);
    }

    @Test
    public void touchKeepsAVisiblePersonBoundAcrossTtl() {
        FakeGallery g = new FakeGallery();
        g.seed(7L, "A");
        g.forcedConfidence = 0.93f;
        PersonIdResolver<String, String> r = resolver(g);
        r.setTrackTtlMs(50L);

        r.beginFrame(0L);
        assertEquals(7L, r.resolve(1, "A", null, 0L).personId);
        int searchesBefore = g.matchCalls;

        for (long t = 16L; t <= 400L; t += 16L) {
            r.beginFrame(t);
            assertTrue("track must stay bound", r.touch(1, t));
            assertEquals(7L, r.personFor(1));
        }
        assertEquals(1, r.activeBindings());
        assertEquals(searchesBefore, g.matchCalls); // no extra gallery searches
    }

    @Test
    public void formatLabelIsZeroPaddedAndHandlesUnknown() {
        assertEquals("P001", PersonIdResolver.formatLabel(1L));
        assertEquals("P012", PersonIdResolver.formatLabel(12L));
        assertEquals("P1001", PersonIdResolver.formatLabel(1001L));
        assertEquals("…", PersonIdResolver.formatLabel(-1L));
    }

    @Test
    public void constructorRejectsNullGallery() {
        try {
            new PersonIdResolver<String, String>(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("gallery"));
        }
    }
}
