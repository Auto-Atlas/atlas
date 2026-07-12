import Foundation

/// Envelope for `GET /v1/approvals` → `{"approvals":[…]}`.
public struct ApprovalList: Codable, Sendable {
    public let approvals: [Approval]
}

/// One staged action awaiting the owner's decision. Mirrors
/// `approval_store._row_to_dict` exactly. Timestamps are Unix epoch **seconds**
/// (`Double`), never ISO strings. `requester`/`summary` are nullable in the schema.
public struct Approval: Codable, Sendable, Identifiable, Equatable {
    public let id: String
    public let tool: String
    public let args: JSONValue
    public let requester: String?
    public let requesterTier: Tier
    public let riskLevel: Risk
    public let summary: String?
    public let status: String
    public let effectiveStatus: String
    public let createdAt: Double
    public let ttlS: Int
    public let expiresAt: Double
    public let secondsLeft: Double
    public let decidedAt: Double?
    public let result: JSONValue?

    enum CodingKeys: String, CodingKey {
        case id, tool, args, requester, summary, status, result
        case requesterTier = "requester_tier"
        case riskLevel = "risk_level"
        case effectiveStatus = "effective_status"
        case createdAt = "created_at"
        case ttlS = "ttl_s"
        case expiresAt = "expires_at"
        case secondsLeft = "seconds_left"
        case decidedAt = "decided_at"
    }

    /// Fail-safe money: cents when `args` matches a recognized per-tool schema, else `nil`.
    /// Never derive an amount from `summary`. `nil` → the UI shows "Amount unavailable".
    public var computedAmountCents: Int? {
        guard tool == "create_invoice",
              let items = args.objectValue?["line_items"]?.arrayValue else { return nil }
        var cents = 0
        for item in items {
            guard let o = item.objectValue,
                  let qty = o["quantity"]?.doubleValue,
                  let rate = o["rate"]?.doubleValue else { return nil }
            cents += Int((qty * rate * 100).rounded())
        }
        return cents
    }

    /// The backend only *releases* known-tier + high-risk approvals remotely; anything
    /// else 409s on approve. The UI renders the hold control only for these.
    public var isReleasable: Bool { requesterTier == .known && riskLevel == .high }

    public var createdDate: Date { Date(timeIntervalSince1970: createdAt) }
}
