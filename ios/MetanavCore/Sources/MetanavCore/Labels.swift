import Foundation

/// Friendly names and typical heights for detector labels (COCO vocabulary).
public enum Labels {
    public static let generic = "Obstacle"

    private static let friendly: [String: String] = [
        "person": "Person", "bicycle": "Bicycle", "car": "Car", "motorcycle": "Motorbike", "motorbike": "Motorbike",
        "bus": "Bus", "train": "Train", "truck": "Truck", "boat": "Boat", "traffic light": "Traffic light",
        "fire hydrant": "Fire hydrant", "stop sign": "Sign", "parking meter": "Parking meter", "bench": "Bench",
        "dog": "Dog", "cat": "Cat", "horse": "Horse", "chair": "Chair", "couch": "Couch", "sofa": "Couch",
        "potted plant": "Plant", "pottedplant": "Plant", "bed": "Bed", "dining table": "Table", "diningtable": "Table",
        "toilet": "Toilet", "tv": "TV", "tvmonitor": "TV", "refrigerator": "Fridge", "suitcase": "Suitcase",
        "backpack": "Bag", "handbag": "Bag", "umbrella": "Umbrella", "bottle": "Bottle", "vase": "Vase",
        "sink": "Sink", "oven": "Oven", "microwave": "Microwave", "laptop": "Laptop", "skateboard": "Skateboard",
        "sports ball": "Ball",
    ]

    /// Approximate real-world heights in meters used for size-based distance estimates.
    private static let heights: [String: Float] = [
        "person": 1.70, "bicycle": 1.00, "motorcycle": 1.10, "motorbike": 1.10, "car": 1.50, "bus": 3.20,
        "truck": 3.00, "chair": 0.90, "bench": 0.85, "fire hydrant": 0.75, "dog": 0.55, "potted plant": 0.70,
        "pottedplant": 0.70, "dining table": 0.75, "diningtable": 0.75, "couch": 0.85, "sofa": 0.85,
        "refrigerator": 1.75, "suitcase": 0.65, "traffic light": 1.00, "stop sign": 0.75,
    ]

    public static func friendlyName(_ raw: String) -> String {
        let key = raw.trimmingCharacters(in: .whitespaces).lowercased()
        if let name = friendly[key] { return name }
        if key.isEmpty { return generic }
        return key.prefix(1).uppercased() + key.dropFirst()
    }

    public static func knownHeightMeters(_ raw: String) -> Float? {
        heights[raw.trimmingCharacters(in: .whitespaces).lowercased()]
    }
}
