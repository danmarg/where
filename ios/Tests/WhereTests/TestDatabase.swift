import Foundation
import Shared

/// An E2eeManager over a database no other test or manager shares.
///
/// `createIosSqlDriver(name: ":memory:")` is not in-memory: SQLiter treats the name as a file
/// name, so every caller would open the same database file and see each other's friends and
/// invites, making tests order-dependent.
func makeTestE2eeManager() -> Shared.E2eeManager {
    Shared.E2eeManager(sqlDriver: Shared.IosSqlDriverKt.createIosSqlDriver(name: "test-\(UUID().uuidString).db"))
}
