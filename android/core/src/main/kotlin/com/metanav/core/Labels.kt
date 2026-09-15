package com.metanav.core

/** Friendly names and typical heights for detector labels (COCO vocabulary). */
object Labels {
    private val friendly = mapOf(
        "person" to "Person",
        "bicycle" to "Bicycle",
        "car" to "Car",
        "motorcycle" to "Motorbike",
        "motorbike" to "Motorbike",
        "bus" to "Bus",
        "train" to "Train",
        "truck" to "Truck",
        "boat" to "Boat",
        "traffic light" to "Traffic light",
        "fire hydrant" to "Fire hydrant",
        "stop sign" to "Sign",
        "parking meter" to "Parking meter",
        "bench" to "Bench",
        "dog" to "Dog",
        "cat" to "Cat",
        "horse" to "Horse",
        "chair" to "Chair",
        "couch" to "Couch",
        "sofa" to "Couch",
        "potted plant" to "Plant",
        "pottedplant" to "Plant",
        "bed" to "Bed",
        "dining table" to "Table",
        "diningtable" to "Table",
        "toilet" to "Toilet",
        "tv" to "TV",
        "tvmonitor" to "TV",
        "refrigerator" to "Fridge",
        "suitcase" to "Suitcase",
        "backpack" to "Bag",
        "handbag" to "Bag",
        "umbrella" to "Umbrella",
        "bottle" to "Bottle",
        "vase" to "Vase",
        "sink" to "Sink",
        "oven" to "Oven",
        "microwave" to "Microwave",
        "laptop" to "Laptop",
        "skateboard" to "Skateboard",
        "sports ball" to "Ball",
    )

    /** Approximate real-world heights in meters used for size-based distance estimates. */
    private val heights = mapOf(
        "person" to 1.70f,
        "bicycle" to 1.00f,
        "motorcycle" to 1.10f,
        "motorbike" to 1.10f,
        "car" to 1.50f,
        "bus" to 3.20f,
        "truck" to 3.00f,
        "chair" to 0.90f,
        "bench" to 0.85f,
        "fire hydrant" to 0.75f,
        "dog" to 0.55f,
        "potted plant" to 0.70f,
        "pottedplant" to 0.70f,
        "dining table" to 0.75f,
        "diningtable" to 0.75f,
        "couch" to 0.85f,
        "sofa" to 0.85f,
        "refrigerator" to 1.75f,
        "suitcase" to 0.65f,
        "traffic light" to 1.00f,
        "stop sign" to 0.75f,
    )

    const val GENERIC = "Obstacle"

    fun friendlyName(raw: String): String {
        val key = raw.trim().lowercase()
        return friendly[key] ?: key.replaceFirstChar { it.uppercase() }.ifBlank { GENERIC }
    }

    fun knownHeightMeters(raw: String): Float? = heights[raw.trim().lowercase()]
}
