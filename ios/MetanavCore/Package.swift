// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MetanavCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MetanavCore", targets: ["MetanavCore"]),
        // `swift run MetanavCoreCheck` exercises the reasoner end to end without XCTest, so the
        // core can be verified from a plain terminal (no Xcode needed).
        .executable(name: "MetanavCoreCheck", targets: ["MetanavCoreCheck"]),
    ],
    targets: [
        .target(name: "MetanavCore"),
        .target(name: "MetanavCoreTestSupport", dependencies: ["MetanavCore"]),
        .executableTarget(name: "MetanavCoreCheck", dependencies: ["MetanavCore", "MetanavCoreTestSupport"]),
        .testTarget(name: "MetanavCoreTests", dependencies: ["MetanavCore", "MetanavCoreTestSupport"]),
    ]
)
