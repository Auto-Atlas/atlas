import XCTest
@testable import AtlasKit

final class AtlasKitSmokeTests: XCTestCase {
    func testVersion() {
        XCTAssertEqual(AtlasKit.version, "0.1.0")
    }
}
