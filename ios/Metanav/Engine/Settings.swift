import Foundation
import MetanavCore

/// User-tunable preferences, persisted in UserDefaults.
struct Settings: Codable, Equatable {
    var source: SourceKind = .glasses
    var units: Units = .meters
    var voice = true
    var haptics = true
    var announceClear = true
    /// Camera height above the ground in meters (glasses ≈ 1.55, phone at chest ≈ 1.3).
    var cameraHeightMeters: Float = 1.55
    /// How far ahead to warn, in meters.
    var alertDistanceMeters: Float = 3.0
    var showPreview = true

    func reasonerConfig() -> ReasonerConfig {
        var config = ReasonerConfig()
        config.geometry = CameraGeometry(heightMeters: cameraHeightMeters)
        config.alertDistanceMeters = alertDistanceMeters
        config.announceClear = announceClear
        config.units = units
        return config
    }

    private static let key = "metanav.settings"

    static func load() -> Settings {
        guard let data = UserDefaults.standard.data(forKey: key),
              let settings = try? JSONDecoder().decode(Settings.self, from: data) else { return Settings() }
        return settings
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) { UserDefaults.standard.set(data, forKey: Self.key) }
    }
}
