import SwiftUI

enum Theme {
    static let ink = Color(red: 0.043, green: 0.059, blue: 0.078)
    static let surface = Color(red: 0.078, green: 0.102, blue: 0.133)
    static let surfaceHigh = Color(red: 0.110, green: 0.141, blue: 0.180)
    static let text = Color(red: 0.949, green: 0.961, blue: 0.969)
    static let muted = Color(red: 0.545, green: 0.596, blue: 0.647)
    static let teal = Color(red: 0.369, green: 0.918, blue: 0.831)
    static let amber = Color(red: 0.961, green: 0.620, blue: 0.043)
    static let red = Color(red: 0.937, green: 0.267, blue: 0.267)
    static let green = Color(red: 0.204, green: 0.827, blue: 0.600)
}

struct Card<Content: View>: View {
    let color: Color
    let content: Content

    init(color: Color = Theme.surface, @ViewBuilder content: () -> Content) {
        self.color = color
        self.content = content()
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(color, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}
