import MWDATCore
import SwiftUI

@main
struct MetanavApp: App {
    @StateObject private var controller: NavigationController

    init() {
        // The Meta Wearables SDK must be configured once, before anything touches Wearables.shared.
        do {
            try Wearables.configure()
        } catch {
            NSLog("[Metanav] Wearables.configure failed: \(error)")
        }
        _controller = StateObject(wrappedValue: NavigationController(wearables: Wearables.shared))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(controller)
                .preferredColorScheme(.dark)
                // Meta AI calls back into the app after registration / permission prompts.
                .onOpenURL { url in
                    Task { _ = try? await Wearables.shared.handleUrl(url) }
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var controller: NavigationController
    @State private var showSettings = false

    var body: some View {
        ZStack {
            Theme.ink.ignoresSafeArea()
            switch controller.runState {
            case .stopped, .failed:
                HomeView(showSettings: $showSettings)
            case .starting, .running:
                NavigateView(showSettings: $showSettings)
            }
        }
        .sheet(isPresented: $showSettings) { SettingsView() }
    }
}
