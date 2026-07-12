import XCTest
@testable import Atlas
@testable import AtlasKit

/// Proves the app-hosted test path works at scaffold time (BMAD Winston #4):
/// both the app target and AtlasKit are importable from AtlasTests.
final class AppHostSmokeTests: XCTestCase {
    func testAtlasKitReachableFromAppHost() {
        XCTAssertEqual(AtlasKit.version, "0.1.0")
    }
}
