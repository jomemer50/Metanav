package com.metanav.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AdvisoryPolicyTest {
    @Test
    fun distancePhrasesReadNaturally() {
        assertEquals("very close", AdvisoryPolicy.distancePhrase(0.9f, Units.METERS))
        assertEquals("1 and a half meters", AdvisoryPolicy.distancePhrase(1.4f, Units.METERS))
        assertEquals("2 meters", AdvisoryPolicy.distancePhrase(2.1f, Units.METERS))
        assertEquals("2 and a half meters", AdvisoryPolicy.distancePhrase(2.6f, Units.METERS))
        assertEquals("3 meters", AdvisoryPolicy.distancePhrase(2.9f, Units.METERS))
        assertEquals("7 feet", AdvisoryPolicy.distancePhrase(2.1f, Units.FEET))
    }

    @Test
    fun phrasesIncludeWhatHowFarAndWhereToGo() {
        val policy = AdvisoryPolicy(ReasonerConfig())
        assertEquals("Chair ahead, 2 meters. Move left.", policy.phrase("Chair", 2.0f, Urgency.CAUTION, Direction.LEFT))
        assertEquals("Stop. Person very close. Move right.", policy.phrase("Person", 0.8f, Urgency.STOP, Direction.RIGHT))
        assertEquals("Stop. Obstacle very close.", policy.phrase("", 0.8f, Urgency.STOP, Direction.STOP))
        assertEquals("Obstacle ahead, 3 meters. Stop.", policy.phrase("Obstacle", 3.0f, Urgency.CAUTION, Direction.STOP))
    }
}
