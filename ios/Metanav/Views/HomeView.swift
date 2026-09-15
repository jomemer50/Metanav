import MWDATCore
import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var controller: NavigationController
    @Binding var showSettings: Bool

    private var registered: Bool { controller.registration == .registered }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Metanav").font(.system(size: 30, weight: .semibold))
                Spacer()
                Button { showSettings = true } label: {
                    Image(systemName: "gearshape").font(.title3).foregroundStyle(Theme.muted)
                }
            }
            .padding(.top, 8)

            Text("Walks with you. Says something only when something is in your way, how far it is, and which way to step.")
                .font(.body).foregroundStyle(Theme.muted).padding(.top, 8)

            glassesCard.padding(.top, 28)

            if case .failed(let message) = controller.runState {
                Card(color: Theme.red.opacity(0.12)) {
                    Text(message).font(.callout).foregroundStyle(Theme.red)
                }.padding(.top, 16)
            }
            if !controller.depthModelBundled {
                Card(color: Theme.amber.opacity(0.12)) {
                    Text("The depth model is not bundled in this build. Run scripts/fetch-models.sh ios, regenerate the project, and rebuild.")
                        .font(.callout).foregroundStyle(Theme.amber)
                }.padding(.top, 16)
            }

            Spacer()

            VStack(spacing: 12) {
                Button { controller.start(.glasses) } label: {
                    Text(registered && !controller.glassesPresent ? "Start with glasses (put them on)" : "Start with glasses")
                        .font(.headline).frame(maxWidth: .infinity).frame(height: 56)
                }
                .buttonStyle(.borderedProminent).tint(Theme.teal).foregroundStyle(Theme.ink)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .disabled(!registered || !controller.depthModelBundled)

                Button { controller.start(.phoneCamera) } label: {
                    Text("Use the phone camera instead").frame(maxWidth: .infinity).frame(height: 52)
                }
                .buttonStyle(.bordered).tint(Theme.muted)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .disabled(!controller.depthModelBundled)

                Text("Guidance is spoken through whatever your phone plays audio on. With the glasses connected, that is the glasses.")
                    .font(.footnote).foregroundStyle(Theme.muted).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        .foregroundStyle(Theme.text)
    }

    private var glassesCard: some View {
        let (label, color): (String, Color) = {
            switch controller.registration {
            case .registered: return controller.glassesPresent ? ("Glasses connected", Theme.green) : ("Registered, glasses not on", Theme.amber)
            case .registering: return ("Connecting…", Theme.amber)
            case .available: return ("Glasses not connected", Theme.muted)
            case .unavailable: return ("Meta AI app not available", Theme.muted)
            }
        }()
        let hint: String = {
            switch controller.registration {
            case .registered: return "Camera frames stream from the glasses over Bluetooth. Tap the glasses' touchpad to pause."
            case .unavailable: return "Install the Meta AI app, pair your glasses, and turn on Developer Mode (Settings › your glasses › Developer Mode)."
            default: return "You'll be sent to the Meta AI app once to approve this app."
            }
        }()
        return Card {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Circle().fill(color).frame(width: 10, height: 10)
                    Text(label).font(.title3.weight(.medium))
                }
                Text(hint).font(.callout).foregroundStyle(Theme.muted)
                switch controller.registration {
                case .registered:
                    Button("Disconnect") { controller.disconnectGlasses() }.font(.callout).tint(Theme.muted).padding(.top, 4)
                case .available:
                    Button("Connect glasses") { controller.connectGlasses() }
                        .buttonStyle(.borderedProminent).tint(Theme.surfaceHigh).padding(.top, 4)
                default:
                    EmptyView()
                }
            }
        }
    }
}
