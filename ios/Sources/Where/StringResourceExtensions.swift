import Foundation
import Shared

extension ResourcesStringResource {
    func localized() -> String {
        return NSLocalizedString(self.resourceId, bundle: self.bundle, comment: "")
    }

    func localized(args: [CVarArg]) -> String {
        let format = self.localized()
        return String(format: format, arguments: args)
    }
}
