// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "AtlasKit",
    platforms: [.iOS(.v17), .watchOS(.v10), .macOS(.v14)],
    products: [.library(name: "AtlasKit", targets: ["AtlasKit"])],
    targets: [
        .target(name: "AtlasKit", swiftSettings: [.swiftLanguageMode(.v6)]),
        .testTarget(
            name: "AtlasKitTests",
            dependencies: ["AtlasKit"],
            resources: [.copy("Fixtures")],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
