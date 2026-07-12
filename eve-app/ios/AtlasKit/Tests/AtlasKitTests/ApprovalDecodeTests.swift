import XCTest
@testable import AtlasKit

final class ApprovalDecodeTests: XCTestCase {
    private func load(_ name: String) throws -> Data {
        let url = Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")!
        return try Data(contentsOf: url)
    }

    func testDecodeInvoiceRow() throws {
        let env = try JSONDecoder().decode(ApprovalList.self, from: load("approvals_sample"))
        let inv = env.approvals[0]
        XCTAssertEqual(inv.id, "9f3c1a2b")
        XCTAssertEqual(inv.tool, "create_invoice")
        XCTAssertEqual(inv.requesterTier, .known)
        XCTAssertEqual(inv.riskLevel, .high)
        XCTAssertEqual(inv.createdAt, 1751720000.0, accuracy: 0.001)  // epoch Double, not ISO
        XCTAssertEqual(inv.computedAmountCents, 19500)               // 3*45 + 1*60 = $195.00
        XCTAssertTrue(inv.isReleasable)
    }

    func testChannelRowHasNoAmount() throws {
        let env = try JSONDecoder().decode(ApprovalList.self, from: load("approvals_sample"))
        let chan = env.approvals[1]
        XCTAssertEqual(chan.tool, "send_to_channel")
        XCTAssertNil(chan.computedAmountCents)   // no invoice schema → fail-safe nil
    }

    func testNullRequesterAndSummaryDoNotCrash() throws {
        let json = #"{"approvals":[{"id":"x","tool":"t","args":{},"requester":null,"requester_tier":"known","risk_level":"high","summary":null,"status":"pending","effective_status":"pending","created_at":1.0,"ttl_s":1,"expires_at":2.0,"seconds_left":1.0,"decided_at":null,"result":null}]}"#
        let env = try JSONDecoder().decode(ApprovalList.self, from: Data(json.utf8))
        XCTAssertNil(env.approvals[0].requester)
        XCTAssertNil(env.approvals[0].summary)
        XCTAssertNil(env.approvals[0].computedAmountCents)  // unknown tool args → nil
    }

    func testUnknownTierAndRiskFallBack() throws {
        let json = #"{"approvals":[{"id":"x","tool":"t","args":{},"requester":"a","requester_tier":"martian","risk_level":"spicy","summary":"s","status":"pending","effective_status":"pending","created_at":1.0,"ttl_s":1,"expires_at":2.0,"seconds_left":1.0,"decided_at":null,"result":null}]}"#
        let env = try JSONDecoder().decode(ApprovalList.self, from: Data(json.utf8))
        XCTAssertEqual(env.approvals[0].requesterTier, .unknown)
        XCTAssertEqual(env.approvals[0].riskLevel, .low)
        XCTAssertFalse(env.approvals[0].isReleasable)
    }
}
