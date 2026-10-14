import Shared
import SwiftUI

/// Shown instead of the map when the database couldn't be opened (see
/// LocationSyncService.databaseOpenError). The app does nothing else then, so it says so, and
/// shows the error itself for a bug report.
struct DatabaseErrorView: View {
    let message: String

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(.orange)
            Text(MR.strings().database_error_title.localized())
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            Text(MR.strings().database_error_message.localized())
                .multilineTextAlignment(.center)
            Text(message)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: 500)
    }
}
