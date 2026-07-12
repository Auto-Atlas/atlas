import Foundation

/// EVE asks the app to capture a camera frame (broadcast as a `capture_frame` event);
/// the app replies by POSTing a `VisionFrame`. Phase-2 UI; DTOs modeled now.
public struct VisionRequest: Codable, Sendable {
    public let request_id: String
    public let prompt: String
    /// Which camera the server aimed this at: `"any"` | `"phone"` | `"glasses"`. The wire
    /// contract (docs/glasses-endpoint-contract.md, approval_api.py `capture_frame`) requires a
    /// surface to IGNORE events naming a different one — otherwise the iPhone answers a request
    /// the user aimed at their glasses. Older servers omit the field; that decodes as `"any"`
    /// (aimed at everyone), which matches the server-side default.
    public let source: String

    public init(request_id: String, prompt: String, source: String = "any") {
        self.request_id = request_id
        self.prompt = prompt
        self.source = source
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        request_id = try c.decode(String.self, forKey: .request_id)
        prompt = try c.decodeIfPresent(String.self, forKey: .prompt) ?? ""
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? "any"
    }

    /// The ignore rule from the contract, in one place: answer only when the request is aimed
    /// at every camera or at this surface by name.
    public func isAimed(at surface: String) -> Bool {
        source == "any" || source == surface
    }
}

public struct VisionFrame: Codable, Sendable {
    public let request_id: String
    public let jpeg_b64: String
    public init(request_id: String, jpeg_b64: String) {
        self.request_id = request_id
        self.jpeg_b64 = jpeg_b64
    }
}
