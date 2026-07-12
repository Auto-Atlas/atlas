import Foundation

/// `GET /v1/status`. Tolerant: `telemetry`/`budget` are null when the desktop is offline,
/// and the telemetry key set drifts, so we decode only the two counters the app needs.
public struct EveStatus: Codable, Sendable {
    public let desktopOnline: Bool
    public let pendingApprovals: Int

    enum CodingKeys: String, CodingKey {
        case desktopOnline = "desktop_online"
        case pendingApprovals = "pending_approvals"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        desktopOnline = (try? c.decode(Bool.self, forKey: .desktopOnline)) ?? false
        pendingApprovals = (try? c.decode(Int.self, forKey: .pendingApprovals)) ?? 0
    }
}
