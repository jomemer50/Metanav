import MetanavCore
import SwiftUI

struct NavigateView: View {
    @EnvironmentObject private var controller: NavigationController
    @Binding var showSettings: Bool

    private var streaming: Bool {
        if case .running(_, .streaming) = controller.runState { return true }
        return false
    }

    private var accent: Color {
        guard streaming else { return Theme.muted }
        switch controller.scene.urgency {
        case .stop: return Theme.red
        case .caution: return Theme.amber
        case .none: return Theme.green
        }
    }

    var body: some View {
        VStack(spacing: 14) {
            HStack {
                Text(sourceLabel).font(.callout).foregroundStyle(Theme.muted)
                Spacer()
                Button { controller.update { $0.voice.toggle() } } label: {
                    Image(systemName: controller.settings.voice ? "speaker.wave.2" : "speaker.slash").foregroundStyle(Theme.muted)
                }
                Button { controller.update { $0.showPreview.toggle() } } label: {
                    Image(systemName: controller.settings.showPreview ? "eye" : "eye.slash").foregroundStyle(Theme.muted)
                }.padding(.horizontal, 10)
                Button { showSettings = true } label: {
                    Image(systemName: "gearshape").foregroundStyle(Theme.muted)
                }
            }
            .font(.title3)

            // Status card: the one thing to read at a glance.
            Card(color: accent.opacity(0.14)) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(streaming ? controller.scene.headline : statusHeadline)
                        .font(.system(size: 44, weight: .semibold)).foregroundStyle(accent)
                        .minimumScaleFactor(0.6).lineLimit(1)
                    let detail = streaming ? controller.scene.detail : statusDetail
                    if !detail.isEmpty {
                        Text(detail).font(.title3).foregroundStyle(Theme.text)
                    }
                    if let last = controller.lastAdvisory {
                        Text("“\(last)”").font(.callout).foregroundStyle(Theme.muted).padding(.top, 6)
                    }
                }
            }
            .animation(.easeInOut(duration: 0.25), value: accent)

            ZStack {
                RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Theme.surface)
                if controller.settings.showPreview, let image = controller.preview {
                    Image(uiImage: image).resizable().scaledToFill()
                } else if !streaming {
                    Text(statusDetail.isEmpty ? "Waiting for frames…" : statusDetail).foregroundStyle(Theme.muted).padding()
                }
                SceneOverlayView(scene: controller.scene, accent: accent, units: controller.settings.units)
            }
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            HStack {
                if controller.stats.inferenceMs > 0 {
                    Text(String(format: "%.1f fps · %d ms", controller.stats.processedFps, controller.stats.inferenceMs))
                        .font(.callout).foregroundStyle(Theme.muted)
                }
                Spacer()
                Button { controller.stop() } label: {
                    Text("Stop").font(.headline).padding(.horizontal, 22).frame(height: 48)
                }
                .buttonStyle(.borderedProminent).tint(Theme.surfaceHigh).foregroundStyle(Theme.text)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .foregroundStyle(Theme.text)
    }

    private var sourceLabel: String {
        switch controller.runState {
        case .running(let kind, _), .starting(let kind): return kind == .glasses ? "Glasses camera" : "Phone camera"
        default: return ""
        }
    }

    private var statusHeadline: String {
        switch controller.runState {
        case .starting: return "Starting"
        case .running(_, let state):
            switch state {
            case .connecting: return "Connecting"
            case .waitingForDevice: return "Waiting"
            case .paused: return "Paused"
            case .streaming: return "Clear"
            case .idle: return "Stopped"
            case .error: return "Problem"
            }
        default: return ""
        }
    }

    private var statusDetail: String {
        switch controller.runState {
        case .starting: return "Loading models and opening the camera"
        case .running(_, let state):
            switch state {
            case .connecting: return "Talking to the glasses"
            case .waitingForDevice: return "Put on the glasses and unfold them"
            case .paused: return "Tap the glasses to resume"
            case .error(let message): return message
            default: return ""
            }
        default: return ""
        }
    }
}

/// Corridor band, a per-column distance strip, and boxes for confirmed obstacles.
struct SceneOverlayView: View {
    let scene: SceneState
    let accent: Color
    let units: Units

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            Canvas { context, _ in
                let edge = Color.white.opacity(0.25)
                var corridor = Path()
                corridor.move(to: CGPoint(x: w * 0.30, y: h * 0.15)); corridor.addLine(to: CGPoint(x: w * 0.30, y: h))
                corridor.move(to: CGPoint(x: w * 0.70, y: h * 0.15)); corridor.addLine(to: CGPoint(x: w * 0.70, y: h))
                context.stroke(corridor, with: .color(edge), lineWidth: 1.5)

                // Distance strip along the bottom: taller and warmer = closer.
                let cols = scene.columnDistances
                if !cols.isEmpty {
                    let stripH = h * 0.16
                    let colW = w / CGFloat(cols.count)
                    for (i, d) in cols.enumerated() where d.isFinite {
                        let closeness = CGFloat(min(max(1 - d / 4, 0.05), 1))
                        let color: Color = d < 1.2 ? Theme.red : (d < 3 ? Theme.amber : Theme.teal)
                        let barH = stripH * closeness
                        context.fill(Path(CGRect(x: CGFloat(i) * colW, y: h - barH, width: colW + 0.5, height: barH)), with: .color(color.opacity(0.75)))
                    }
                }

                for o in scene.obstacles {
                    let rect = CGRect(x: CGFloat(o.left) * w, y: h * 0.35, width: CGFloat(o.right - o.left) * w, height: h * 0.45)
                    let color = o.inCorridor ? accent : Color.white.opacity(0.5)
                    context.stroke(Path(roundedRect: rect, cornerRadius: 6), with: .color(color), lineWidth: 2)
                }
            }
            ForEach(scene.obstacles) { o in
                Text("\(o.label) · \(AdvisoryPolicy.shortDistance(o.distanceMeters, units: units))")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(o.inCorridor ? accent : Theme.muted)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Theme.ink.opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
                    .position(x: CGFloat(o.left) * w + 60, y: h * 0.31)
            }
        }
        .allowsHitTesting(false)
    }
}
