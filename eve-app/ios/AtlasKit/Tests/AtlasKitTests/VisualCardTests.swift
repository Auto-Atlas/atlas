import XCTest
@testable import AtlasKit

final class VisualCardTests: XCTestCase {
    func testNoteWithTextGetsDefaultTitle() {
        let c = VisualCard.parse(kind: "note", title: "", visualId: "", url: "", text: "hi")
        XCTAssertEqual(c?.kind, .note)
        XCTAssertEqual(c?.title, "From EVE")
        XCTAssertEqual(c?.text, "hi")
    }

    func testNoteEmptyTextDropped() {
        XCTAssertNil(VisualCard.parse(kind: "note", title: "", visualId: "", url: "", text: ""))
    }

    func testImageRequiresHexId() {
        XCTAssertNil(VisualCard.parse(kind: "image", title: "", visualId: "NOTHEX!", url: "", text: ""))
        XCTAssertNotNil(VisualCard.parse(kind: "image", title: "", visualId: "a1b2c3d4", url: "", text: ""))
    }

    func testDesktopScreenDefaultTitle() {
        let c = VisualCard.parse(kind: "desktop_screen", title: "", visualId: "a1b2c3d4", url: "", text: "")
        XCTAssertEqual(c?.kind, .desktopScreen)
        XCTAssertEqual(c?.title, "Your desktop")
    }

    func testUnknownKindDropped() {
        XCTAssertNil(VisualCard.parse(kind: "banana", title: "t", visualId: "", url: "", text: ""))
    }
}
