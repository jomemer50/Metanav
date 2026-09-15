import MetanavCore
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var controller: NavigationController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Feedback") {
                    Toggle("Voice guidance", isOn: binding(\.voice))
                    Toggle("Vibrate on the phone", isOn: binding(\.haptics))
                    Toggle("Say \"path clear\" afterwards", isOn: binding(\.announceClear))
                    Picker("Units", selection: binding(\.units)) {
                        Text("Meters").tag(Units.meters)
                        Text("Feet").tag(Units.feet)
                    }
                }
                Section {
                    VStack(alignment: .leading) {
                        HStack {
                            Text("Warn within")
                            Spacer()
                            Text(distanceLabel(controller.settings.alertDistanceMeters)).foregroundStyle(Theme.teal)
                        }
                        Slider(value: binding(\.alertDistanceMeters), in: 1.5...5, step: 0.5)
                    }
                    VStack(alignment: .leading) {
                        HStack {
                            Text("Camera height")
                            Spacer()
                            Text(String(format: "%.2f m", controller.settings.cameraHeightMeters)).foregroundStyle(Theme.teal)
                        }
                        Slider(value: binding(\.cameraHeightMeters), in: 0.9...2.0, step: 0.05)
                        Text("Glasses: your eye height. Phone held at the chest: about 1.3 m. Distances depend on this.")
                            .font(.footnote).foregroundStyle(Theme.muted)
                    }
                } header: {
                    Text("Distances")
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.surface)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.large])
        .tint(Theme.teal)
    }

    private func binding<T>(_ keyPath: WritableKeyPath<Settings, T>) -> Binding<T> {
        Binding(
            get: { controller.settings[keyPath: keyPath] },
            set: { value in controller.update { $0[keyPath: keyPath] = value } }
        )
    }

    private func distanceLabel(_ meters: Float) -> String {
        controller.settings.units == .meters ? String(format: "%.1f m", meters) : "\(Int((meters * 3.28084).rounded())) ft"
    }
}
