import XCTest
@preconcurrency import Shared
@testable import Where

/// The E2EE database holds live ratchet state; a restored backup would roll back send
/// counters and reuse (key, nonce) pairs (spec §5.5). It must be excluded from backups.
final class DatabaseBackupExclusionTests: XCTestCase {
    func testDatabaseFileLivesInAnExcludedDirectory() throws {
        let name = "backup-exclusion-test.db"
        let driver = Shared.IosSqlDriverKt.createIosSqlDriver(name: name)
        defer { driver.close() }

        // The driver opens lazily; loading the store forces SQLite to create the file.
        _ = Shared.E2eeManager(sqlDriver: driver)

        let dir = Shared.IosSqlDriverKt.iosDatabaseDirectory(name: name)
        // The file SQLite actually created is in the directory the app excludes and verifies,
        // so the check isn't just the helper agreeing with itself.
        let contents = (try? FileManager.default.contentsOfDirectory(atPath: dir)) ?? []
        XCTAssertTrue(FileManager.default.fileExists(atPath: "\(dir)/\(name)"), "\(dir) contains \(contents)")
        XCTAssertTrue(LocationSyncService.isExcludedFromBackup(path: dir))
    }
}
