package com.metanav.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroundPlaneTest {
    @Test
    fun groundDepthDecreasesTowardBottomOfFrame() {
        val model = GroundPlaneModel(CameraGeometry(), rows = 100)
        assertTrue(model.groundDepth(0).isInfinite() || model.groundDepth(0) > model.groundDepth(50))
        assertTrue(model.groundDepth(50) > model.groundDepth(80))
        assertTrue(model.groundDepth(80) > model.groundDepth(99))
        assertTrue(model.groundDepth(99) > 0.5f)
    }

    @Test
    fun calibrationRecoversMetricDepthFromAffineDisparity() {
        val scene = SyntheticScene()
        val calibrator = DepthScaleCalibrator()
        val ground = GroundPlaneModel(scene.geometry, scene.height)
        val depth = scene.render()
        assertTrue(calibrator.update(depth, ground))
        // A pixel in the middle of the frame should map back to its geometric ground distance.
        val row = (scene.height * 0.7f).toInt()
        val z = calibrator.metricDepth(depth[scene.width / 2, row])
        val expected = ground.groundDepth(row)
        assertTrue(abs(z - expected) / expected < 0.05f, "expected $expected got $z")
    }

    @Test
    fun calibrationRejectsFlatBottomOfFrame() {
        val scene = SyntheticScene()
        val calibrator = DepthScaleCalibrator()
        val ground = GroundPlaneModel(scene.geometry, scene.height)
        // A wall 0.6 m away covering the whole frame gives a flat profile: no ground to fit.
        val wall = scene.render(listOf(SyntheticScene.Box(0f, 1f, 0.6f, 3f)))
        assertEquals(false, calibrator.update(wall, ground))
        assertEquals(false, calibrator.isCalibrated)
    }

    @Test
    fun calibrationSurvivesNoise() {
        val scene = SyntheticScene()
        val calibrator = DepthScaleCalibrator()
        val ground = GroundPlaneModel(scene.geometry, scene.height)
        assertTrue(calibrator.update(scene.render(noise = 0.05f), ground))
    }
}
