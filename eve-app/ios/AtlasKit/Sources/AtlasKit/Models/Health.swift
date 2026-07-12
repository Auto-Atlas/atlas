import Foundation

/// `GET /v1/health`. Extra keys (releasing_orphans, remote_approval_enabled, …) are
/// safely ignored — we decode only what the app uses.
public struct Health: Codable, Sendable {
    public let ok: Bool
    public let service: String
    public let pending: Int
}
