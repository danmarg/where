import Foundation
import XCTest
import os
@testable import Where

/// The BGTask heartbeat launch handler, run as the system runs it: on a background queue, not the
/// main thread. A handler that Swift infers as main-actor-isolated traps on entry there
/// (`_dispatch_assert_queue_fail`, the 1.31 crash, #457). `BGTaskScheduler` itself isn't exercised:
/// it can't be driven from the simulator or a unit test, so a fake scheduler captures the handler
/// that `HeartbeatTaskRegistration` registers, which is the same code production runs.
final class HeartbeatTaskRegistrationTests: XCTestCase {
    typealias Handler = @Sendable (any HeartbeatTask) -> Void

    final class FakeScheduler: HeartbeatScheduling, @unchecked Sendable {
        private let lock = NSLock()
        private var _identifier: String?
        private var _handler: Handler?
        var identifier: String? { lock.withLock { _identifier } }
        var handler: Handler? { lock.withLock { _handler } }
        func register(identifier: String, handler: @escaping Handler) {
            lock.withLock {
                _identifier = identifier
                _handler = handler
            }
        }
    }

    final class FakeTask: HeartbeatTask, @unchecked Sendable {
        private let lock = NSLock()
        private var _completions: [Bool] = []
        private var _expiration: (@Sendable () -> Void)?
        let completed = XCTestExpectation(description: "task completed")
        var completions: [Bool] { lock.withLock { _completions } }
        var expiration: (@Sendable () -> Void)? { lock.withLock { _expiration } }
        func setExpirationHandler(_ handler: @escaping @Sendable () -> Void) {
            lock.withLock { _expiration = handler }
        }
        func markTaskCompleted(success: Bool) {
            lock.withLock { _completions.append(success) }
            completed.fulfill()
        }
    }

    private let launchQueue = DispatchQueue(label: "test.bgtask.launch", qos: .utility)

    private func registered(
        poll: @escaping @Sendable () async -> Void,
        scheduleNext: @escaping @Sendable () -> Void = {}
    ) throws -> (FakeScheduler, Handler) {
        let scheduler = FakeScheduler()
        HeartbeatTaskRegistration.register(with: scheduler, poll: poll, scheduleNext: scheduleNext)
        return (scheduler, try XCTUnwrap(scheduler.handler, "nothing was registered"))
    }

    func testRegistersTheIdentifierDeclaredInInfoPlist() throws {
        let (scheduler, _) = try registered(poll: {})
        XCTAssertEqual(scheduler.identifier, HeartbeatTaskRegistration.identifier)

        // A mismatch makes BGTaskScheduler.submit fail (and registration crash) at runtime.
        let permitted = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]
        XCTAssertEqual(permitted?.contains(HeartbeatTaskRegistration.identifier), true,
                       "Info.plist BGTaskSchedulerPermittedIdentifiers must list the heartbeat identifier")
    }

    func testLaunchedOnABackgroundQueue_pollsCompletesAndReschedules() async throws {
        let polled = XCTestExpectation(description: "poll ran")
        let pollWasOnMainThread = OSAllocatedUnfairLock(initialState: true)
        let rescheduled = OSAllocatedUnfairLock(initialState: 0)
        let (_, handler) = try registered(
            poll: {
                pollWasOnMainThread.withLock { $0 = Thread.isMainThread }
                polled.fulfill()
            },
            scheduleNext: { rescheduled.withLock { $0 += 1 } }
        )

        let task = FakeTask()
        // Exactly as BGTaskScheduler does with a nil queue: a background queue, never main.
        launchQueue.async { handler(task) }
        await fulfillment(of: [polled, task.completed], timeout: 5)

        XCTAssertEqual(task.completions, [true], "a finished poll completes the task successfully, once")
        XCTAssertEqual(rescheduled.withLock { $0 }, 1, "the next heartbeat must be scheduled exactly once")
        XCTAssertFalse(pollWasOnMainThread.withLock { $0 }, "the poll should not have been forced onto the main thread")
    }

    func testExpirationFromABackgroundQueue_cancelsThePollAndStillReschedules() async throws {
        let pollStarted = XCTestExpectation(description: "poll started")
        let pollSawCancel = XCTestExpectation(description: "poll cancelled")
        let rescheduled = OSAllocatedUnfairLock(initialState: 0)
        let (_, handler) = try registered(
            poll: {
                pollStarted.fulfill()
                while !Task.isCancelled { try? await Task.sleep(for: .milliseconds(10)) }
                pollSawCancel.fulfill()
            },
            scheduleNext: { rescheduled.withLock { $0 += 1 } }
        )

        let task = FakeTask()
        launchQueue.async { handler(task) }
        await fulfillment(of: [pollStarted], timeout: 5)

        // The system delivers expiration on an arbitrary queue too.
        let expire = try XCTUnwrap(task.expiration, "the handler must install an expiration handler")
        DispatchQueue.global(qos: .utility).async { expire() }
        await fulfillment(of: [pollSawCancel, task.completed], timeout: 5)

        XCTAssertEqual(task.completions, [false], "expiry completes the task unsuccessfully, once")
        XCTAssertEqual(rescheduled.withLock { $0 }, 1, "an expired task must still schedule the next one")
    }
}
