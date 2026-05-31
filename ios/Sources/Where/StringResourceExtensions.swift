import Foundation
import Shared

extension ResourcesStringResource {
    func localized() -> String {
        return NSLocalizedString(self.resourceId, bundle: self.bundle, comment: "")
    }

    func localized(args: [CVarArg]) -> String {
        // moko-resources XML strings use Android's String.format conventions:
        // %s for String, %d for Int. Swift's String(format:) accepts %d but
        // requires %@ for String — normalize before formatting so a shared
        // template works on both platforms.
        let format = self.localized().replacingOccurrences(of: "%s", with: "%@")
        return String(format: format, arguments: args)
    }
}
