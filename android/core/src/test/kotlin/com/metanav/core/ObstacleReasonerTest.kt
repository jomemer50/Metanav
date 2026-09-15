package com.metanav.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObstacleReasonerTest {
    private val frameMs = 150L

    private fun run(
        reasoner: ObstacleReasoner,
        scene: SyntheticScene,
        frames: Int,
        startMs: Long = 0L,
        detections: List<Detection> = emptyList(),
        boxesAt: (Int) -> List<SyntheticScene.Box>,
    ): List<Pair<Long, ReasonerOutput>> {
        val out = ArrayList<Pair<Long, ReasonerOutput>>()
        for (i in 0 until frames) {
            val t = startMs + i * frameMs
            val depth = scene.render(boxesAt(i), noise = 0.02f, seed = i + 7)
            out.add(t to reasoner.process(FrameObservation(t, depth, detections)))
        }
        return out
    }

    @Test
    fun flatGroundStaysQuiet() {
        val reasoner = ObstacleReasoner()
        val results = run(reasoner, SyntheticScene(), frames = 15) { emptyList() }
        assertTrue(results.all { it.second.advisory == null })
        assertEquals(Urgency.NONE, results.last().second.scene.urgency)
        assertTrue(results.last().second.scene.calibrated)
        assertEquals("Clear", results.last().second.scene.headline)
    }

    @Test
    fun boxInPathIsAnnouncedOnceWithDistanceAndDirection() {
        val reasoner = ObstacleReasoner()
        val box = SyntheticScene.Box(left = 0.40f, right = 0.70f, distance = 2.0f)
        val results = run(reasoner, SyntheticScene(), frames = 20) { listOf(box) }
        val advisories = results.mapNotNull { it.second.advisory }
        assertEquals(1, advisories.size, "expected exactly one advisory, got $advisories")
        val a = advisories.first()
        assertEquals(Urgency.CAUTION, a.urgency)
        assertTrue(abs(a.distanceMeters - 2.0f) < 0.4f, "distance was ${a.distanceMeters}")
        assertEquals(Direction.LEFT, a.direction) // box sits right of center
        assertTrue(a.text.startsWith("Obstacle ahead, 2 meters"), a.text)
        assertTrue(a.text.endsWith("Move left."), a.text)
        // Confirmation needs a few frames: nothing on frame 1.
        assertNull(results[0].second.advisory)
        assertEquals(Urgency.CAUTION, results.last().second.scene.urgency)
    }

    @Test
    fun detectorLabelNamesTheObstacle() {
        val reasoner = ObstacleReasoner()
        val box = SyntheticScene.Box(left = 0.30f, right = 0.55f, distance = 2.2f, heightMeters = 1.7f)
        val person = Detection("person", 0.9f, NormRect(0.30f, 0.20f, 0.25f, 0.70f))
        val results = run(reasoner, SyntheticScene(), frames = 12, detections = listOf(person)) { listOf(box) }
        val a = results.mapNotNull { it.second.advisory }.first()
        assertEquals("Person", a.label)
        assertTrue(a.text.startsWith("Person ahead"), a.text)
        assertEquals(Direction.RIGHT, a.direction)
    }

    @Test
    fun offPathObstacleIsNotAnnounced() {
        val reasoner = ObstacleReasoner()
        val box = SyntheticScene.Box(left = 0.02f, right = 0.22f, distance = 1.5f)
        val results = run(reasoner, SyntheticScene(), frames = 15) { listOf(box) }
        assertTrue(results.all { it.second.advisory == null })
        assertEquals(Urgency.NONE, results.last().second.scene.urgency)
        // It still shows up in the scene for the overlay.
        assertTrue(results.last().second.scene.obstacles.any { !it.inCorridor })
    }

    @Test
    fun flickerIsIgnored() {
        val reasoner = ObstacleReasoner()
        val box = SyntheticScene.Box(left = 0.40f, right = 0.65f, distance = 2.0f)
        val results = run(reasoner, SyntheticScene(), frames = 25) { i -> if (i % 5 == 0) listOf(box) else emptyList() }
        assertTrue(results.all { it.second.advisory == null })
    }

    @Test
    fun approachingObstacleEscalatesWithoutNagging() {
        val reasoner = ObstacleReasoner()
        val results = run(reasoner, SyntheticScene(), frames = 40) { i ->
            val d = (3.0f - i * 0.065f).coerceAtLeast(0.7f)
            listOf(SyntheticScene.Box(left = 0.38f, right = 0.62f, distance = d))
        }
        val advisories = results.mapNotNull { it.second.advisory }
        assertTrue(advisories.size in 2..4, "advisories: ${advisories.map { it.text }}")
        assertEquals(Urgency.CAUTION, advisories.first().urgency)
        assertEquals(Urgency.STOP, advisories.last().urgency)
        assertTrue(advisories.last().text.startsWith("Stop."), advisories.last().text)
        // Each re-announcement is for a closer distance.
        for (i in 1 until advisories.size) assertTrue(advisories[i].distanceMeters < advisories[i - 1].distanceMeters)
    }

    @Test
    fun pathClearIsSpokenAfterObstacleLeaves() {
        val reasoner = ObstacleReasoner()
        val box = SyntheticScene.Box(left = 0.40f, right = 0.65f, distance = 2.0f)
        val results = run(reasoner, SyntheticScene(), frames = 40) { i -> if (i < 12) listOf(box) else emptyList() }
        val texts = results.mapNotNull { it.second.advisory?.text }
        assertEquals(2, texts.size, texts.toString())
        assertEquals("Path clear", texts.last())
    }

    @Test
    fun wallFillingTheFrameTriggersStopUsingFrozenCalibration() {
        val reasoner = ObstacleReasoner()
        val wall = SyntheticScene.Box(left = 0f, right = 1f, distance = 0.7f, heightMeters = 3f)
        val results = run(reasoner, SyntheticScene(), frames = 20) { i -> if (i >= 6) listOf(wall) else emptyList() }
        val advisories = results.mapNotNull { it.second.advisory }
        assertTrue(advisories.isNotEmpty(), "expected a stop advisory")
        assertEquals(Urgency.STOP, advisories.first().urgency)
        assertEquals(Direction.STOP, advisories.first().direction)
        assertTrue(advisories.first().distanceMeters < 1.2f)
    }

    @Test
    fun persistentObstacleGetsLimitedReminders() {
        val reasoner = ObstacleReasoner()
        val box = SyntheticScene.Box(left = 0.40f, right = 0.65f, distance = 2.0f)
        // 40 seconds of standing in front of the same thing.
        val results = run(reasoner, SyntheticScene(), frames = 270) { listOf(box) }
        val advisories = results.mapNotNull { it.second.advisory }
        assertEquals(1 + ReasonerConfig().maxReminders, advisories.size, advisories.map { it.text }.toString())
    }

    @Test
    fun nothingHappensWithoutDepth() {
        val reasoner = ObstacleReasoner()
        val out = reasoner.process(FrameObservation(0L, null))
        assertNull(out.advisory)
        assertNotNull(out.scene)
    }
}
