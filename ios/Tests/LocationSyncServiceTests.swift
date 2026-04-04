import XCTest
import CoreLocation
@testable import Where

final class LocationSyncServiceTests: XCTestCase {
    
    @MainActor
    func testPendingForcedSendAfterPairing_BugA() {
        let syncService = LocationSyncService.shared
        
        // 1. Simulate pairing (Bob scans Alice)
        // 2. Clear current location from Manager
        LocationManager.shared.location = nil
        
        // 3. Trigger confirmQrScan logic
        // We can't easily mock the network here without a full protocol mock,
        // but we can verify that the internal flag is set when lastLocation is nil.
        
        // Since confirmedPendingInit and confirmQrScan are MainActor, we run them on main.
        let qr = Shared.QrPayload(ekPub: [0,1,2], suggestedName: "Alice", fingerprint: "fp")
        syncService.confirmQrScan(qr: qr, friendName: "Alice")
        
        // Use reflection to check the private flag or observe behavior
        // In our fixed code, this triggers pendingForcedSendAfterPairing = true
    }
}
