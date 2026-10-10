import XCTest
@preconcurrency import Shared

/// The E2EE database holds live ratchet state; a restored backup would roll back send
/// counters and reuse (key, nonce) pairs (spec §5.5). It must be excluded from backups.
final class DatabaseBackupExclusionTests: XCTestCase {
    func testDatabaseDirectoryIsExcludedFromBackup() throws {
        let name = "backup-exclusion-test.db"
        let driver = Shared.IosSqlDriverKt.createIosSqlDriver(name: name)
        defer { driver.close() }

        let dir = URL(fileURLWithPath: Shared.IosSqlDriverKt.iosDatabaseDirectory(name: name))
        let values = try dir.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }
}
