import XCTest
@testable import AtlasKit

/// Pins the surface-routing side of the `capture_frame` contract: a request aimed at another
/// surface must be ignorable, and a server that predates `source` decodes as aimed-at-everyone.
final class VisionRequestTests: XCTestCase {

    func testDecodesSourceFromTheWire() throws {
        let json = #"{"request_id":"r1","prompt":"what am I holding","source":"glasses"}"#
        let req = try JSONDecoder().decode(VisionRequest.self, from: Data(json.utf8))
        XCTAssertEqual(req.source, "glasses")
        XCTAssertTrue(req.isAimed(at: "glasses"))
        XCTAssertFalse(req.isAimed(at: "phone"), "phone must ignore a glasses-aimed capture")
    }

    func testMissingSourceDecodesAsAny() throws {
        let json = #"{"request_id":"r2","prompt":""}"#
        let req = try JSONDecoder().decode(VisionRequest.self, from: Data(json.utf8))
        XCTAssertEqual(req.source, "any")
        XCTAssertTrue(req.isAimed(at: "phone"))
        XCTAssertTrue(req.isAimed(at: "glasses"))
    }

    func testMissingPromptDecodesEmpty() throws {
        let json = #"{"request_id":"r3","source":"phone"}"#
        let req = try JSONDecoder().decode(VisionRequest.self, from: Data(json.utf8))
        XCTAssertEqual(req.prompt, "")
        XCTAssertTrue(req.isAimed(at: "phone"))
    }

    func testRoundTripKeepsSnakeCaseKeys() throws {
        let data = try JSONEncoder().encode(VisionRequest(request_id: "r4", prompt: "p", source: "phone"))
        let s = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(s.contains("\"request_id\""), s)
        XCTAssertTrue(s.contains("\"source\""), s)
    }
}
