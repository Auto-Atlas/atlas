import Foundation

/// Speaker-ID trust tier. Decodes leniently — an unrecognized value from a newer
/// server never crashes decode; it falls back to `.unknown` (which, per the design
/// system, never gets an Approve affordance).
public enum Tier: String, Codable, Sendable {
    case owner, known, kid, unknown

    public init(from decoder: Decoder) throws {
        let raw = (try? decoder.singleValueContainer().decode(String.self)) ?? ""
        self = Tier(rawValue: raw) ?? .unknown
    }
}

/// Risk level of a staged action. Lenient decode → unknown values fall back to `.low`.
public enum Risk: String, Codable, Sendable {
    case low, medium, high

    public init(from decoder: Decoder) throws {
        let raw = (try? decoder.singleValueContainer().decode(String.self)) ?? ""
        self = Risk(rawValue: raw) ?? .low
    }
}
