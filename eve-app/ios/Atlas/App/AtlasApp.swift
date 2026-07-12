import SwiftUI
import AtlasKit

@main
struct AtlasApp: App {
    var body: some Scene {
        WindowGroup {
            Text("Atlas \(AtlasKit.version)")
        }
    }
}
