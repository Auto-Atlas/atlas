import Foundation

/// A visual EVE pushes to the screen (surface_visual). Mirrors Android `SurfaceVisual`:
/// parse applies drop-if-malformed rules so a bad announce renders nothing rather than a
/// broken card. Phase-2 UI; the model + rules + tests live here so the watch reuses them.
public struct VisualCard: Sendable, Equatable {
    public enum Kind: String, Sendable {
        case desktopScreen = "desktop_screen"
        case image
        case note
    }

    public let kind: Kind
    public let title: String
    public let visualId: String
    public let url: String
    public let text: String

    private static let hexPattern = try! NSRegularExpression(pattern: "^[a-f0-9]{8,32}$")
    private static func isHex(_ s: String) -> Bool {
        hexPattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    /// Returns `nil` (drop) when: unknown/blank kind; image kinds without a hex `visualId`;
    /// a `note` with empty text. Blank titles get a per-kind default.
    public static func parse(kind: String, title: String, visualId: String, url: String, text: String) -> VisualCard? {
        guard let k = Kind(rawValue: kind) else { return nil }
        let t = String(title.prefix(120))
        let body = String(text.prefix(8000))
        switch k {
        case .note:
            guard !body.isEmpty else { return nil }
            return VisualCard(kind: k, title: t.isEmpty ? "From EVE" : t, visualId: "", url: url, text: body)
        case .image, .desktopScreen:
            guard isHex(visualId) else { return nil }
            let def = k == .image ? "Image" : "Your desktop"
            return VisualCard(kind: k, title: t.isEmpty ? def : t, visualId: visualId, url: url, text: body)
        }
    }
}
